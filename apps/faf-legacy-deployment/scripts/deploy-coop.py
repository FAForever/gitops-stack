#!/usr/bin/env python3

"""
  Mod updater script

  This script packs up the coop mod files, writes them to /opt/faf/data/content/legacy-featured-mod-files/.../, and updates the database.

  Code is mostly self-explanatory - haha, fat chance! Read it from bottom to top and don't blink. Blink and you're dead, no wait where were we?
  To adapt this duct-tape based blob of shit for new mission voice overs, just change files array at the very bottom.

  Environment variables required:
    PATCH_VERSION
    DATABASE_HOST
    DATABASE_NAME
    DATABASE_USERNAME
    DATABASE_PASSWORD
"""
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import urllib.error
import zipfile

import mysql.connector

FIXED_ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)  # year, month, day, hour, min, sec


def get_db_connection():
    """Establish and return a MySQL connection using environment variables."""
    host = os.getenv("DATABASE_HOST", "localhost")
    db = os.getenv("DATABASE_NAME", "faf")
    user = os.getenv("DATABASE_USERNAME", "root")
    password = os.getenv("DATABASE_PASSWORD", "banana")

    return mysql.connector.connect(
        host=host,
        user=user,
        password=password,
        database=db,
        charset="utf8mb4",
        collation="utf8mb4_unicode_ci",
    )


def read_db(conn, mod):
    """
      Read latest versions and md5's from db
      Returns dict {fileId: {version, name, md5}}
    """
    query = f"""
        SELECT uf.fileId, uf.version, uf.name, uf.md5
        FROM (
            SELECT fileId, MAX(version) AS version
            FROM updates_{mod}_files
            GROUP BY fileId
        ) AS maxthings
        INNER JOIN updates_{mod}_files AS uf
        ON maxthings.fileId = uf.fileId AND maxthings.version = uf.version;
    """

    with conn.cursor() as cursor:
        cursor.execute(query)

        oldfiles = {}
        for (fileId, version, name, md5) in cursor.fetchall():
            oldfiles[int(fileId)] = {
                "version": version,
                "name": name,
                "md5": md5,
            }

        return oldfiles


def update_db(conn, mod, fileId, version, name, md5, dryrun):
    """
    Delete and reinsert a file record in updates_{mod}_files
    """
    delete_query = f"DELETE FROM updates_{mod}_files WHERE fileId=%s AND version=%s"
    insert_query = f"""
        INSERT INTO updates_{mod}_files (fileId, version, name, md5, obselete)
        VALUES (%s, %s, %s, %s, 0)
    """

    print(f"Updating DB for {name} (fileId={fileId}, version={version})")

    if not dryrun:
        try:
            with conn.cursor() as cursor:
                cursor.execute(delete_query, (fileId, version))
                cursor.execute(insert_query, (fileId, version, name, md5))
                conn.commit()
        except mysql.connector.Error as err:
            print(f"MySQL error while updating {name}: {err}")
            conn.rollback()
            exit(1)
    else:
        print(f"Dryrun: would run for {name}")

def calc_md5(fname):
    hash_md5 = hashlib.md5()
    with open(fname, "rb") as f:
        for chunk in iter(lambda: f.read(4096), b""):
            hash_md5.update(chunk)
    return hash_md5.hexdigest()

def zipdir(path, ziph):
    if not os.path.exists(path):
        print(f"Warning: {path} does not exist, skipping")
        return

    if os.path.isdir(path):
        for root, dirs, files in os.walk(path):
            files.sort()  # deterministic order
            dirs.sort()   # deterministic order
            for file in files:
                full_path = os.path.join(root, file)
                arcname = os.path.relpath(full_path, start=path)  # preserve folder structure
                info = zipfile.ZipInfo(arcname, FIXED_ZIP_TIMESTAMP)
                with open(full_path, "rb") as f:
                    data = f.read()
                ziph.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED)
    else:
        # single file outside a directory
        arcname = os.path.basename(path)
        info = zipfile.ZipInfo(arcname, FIXED_ZIP_TIMESTAMP)
        with open(path, "rb") as f:
            data = f.read()
        ziph.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED)



def create_file(conn, mod, fileId, version, name, source, target_dir, old_md5, dryrun):
    """Pack or copy files, compare MD5, update DB if changed."""
    target_dir = os.path.join(target_dir, f"updates_{mod}_files")
    os.makedirs(target_dir, exist_ok=True)

    name = name.format(version)
    target_name = os.path.join(target_dir, name)

    print(f"Processing {name} (fileId {fileId})")

    if isinstance(source, list):
        print(f"Zipping {source} -> {target_name}")
        fd, fname = tempfile.mkstemp("_" + name, "patcher_")
        os.close(fd)
        with zipfile.ZipFile(fname, "w", zipfile.ZIP_DEFLATED) as zf:
            for sm in source:
                zipdir(sm, zf)
        rename = True
        checksum = calc_md5(fname)
    else:
        rename = False
        fname = source
        if source is None:
            checksum = calc_md5(target_name) if os.path.exists(target_name) else None
        else:
            checksum = calc_md5(fname)

    if checksum is None:
        print(f"Skipping {name} (no source file and no existing file to checksum)")
        return

    print(f"Compared checksums: Old {old_md5} New {checksum}")

    # Otherwise proceed with copy + DB update
    print(f"Detected content change for {name}: old md5={old_md5}, new md5={checksum}")
    if fname is not None:
        print(f"Copying {fname} -> {target_name}")
        if not dryrun:
            shutil.copy(fname, target_name)
    else:
        print("No source file, not moving")

    if os.path.exists(target_name):
        update_db(conn, mod, fileId, version, name, checksum, dryrun)
        if not dryrun:
            try:
                os.chmod(target_name, 0o664)
            except PermissionError:
                print(f"Warning: Could not chmod {target_name}")
    else:
        print(f"Target file {target_name} does not exist, not updating db")


def do_files(conn, mod, version, files, target_dir, dryrun):
    """Process all files for given mod/version."""
    current_files = read_db(conn, mod)
    for name, fileId, source in files:
        old_md5 = current_files.get(fileId, {}).get("md5")
        create_file(conn, mod, fileId, version, name, source, target_dir, old_md5, dryrun)


def prepare_repo():
    """Clone or update the fa-coop repository and checkout the specified ref."""
    repo_url = os.getenv("GIT_REPO_URL", "https://github.com/FAForever/fa-coop.git")
    git_ref = os.getenv("GIT_REF", "v" + os.getenv("PATCH_VERSION"))
    workdir = os.getenv("GIT_WORKDIR", "/tmp/fa-coop")

    if not git_ref:
        print("Error: GIT_REF or PATCH_VERSION must be specified.")
        sys.exit(1)

    print(f"=== Preparing repository {repo_url} at ref {git_ref} in {workdir} ===")

    # Clone if not exists
    if not os.path.isdir(os.path.join(workdir, ".git")):
        print(f"Cloning repository into {workdir} ...")
        subprocess.check_call(["git", "clone", repo_url, workdir])
    else:
        print(f"Repository already exists in {workdir}, fetching latest changes...")
        subprocess.check_call(["git", "-C", workdir, "fetch", "--all", "--tags"])

    # Checkout the desired ref
    print(f"Checking out {git_ref} ...")
    subprocess.check_call(["git", "-C", workdir, "fetch", "--tags"])
    subprocess.check_call(["git", "-C", workdir, "checkout", git_ref])

    print(f"=== Repository ready at {workdir} ===")
    return workdir


def download_vo_assets(version, target_dir):
    """
    Download VO .nx2 files from latest GitHub release of fa-coop,
    rename them for the given patch version, and copy to target directory.
    """
    os.makedirs(target_dir, exist_ok=True)
    print(f"Fetching VO assets for patch version {version}...")

    # 1. Get latest release JSON from GitHub
    api_url = f"https://api.github.com/repos/FAForever/fa-coop/releases/tags/v{version}"
    with urllib.request.urlopen(api_url) as response:
        release_info = json.load(response)

    # 2. Filter assets ending with .nx2
    nx2_urls = [
        asset["browser_download_url"]
        for asset in release_info.get("assets", [])
        if asset["browser_download_url"].endswith(".nx2")
    ]

    if not nx2_urls:
        print("No VO .nx2 assets found in the latest release.")
        return

    temp_dir = os.path.join("/tmp", f"vo_download_{version}")
    os.makedirs(temp_dir, exist_ok=True)

    # 3. Download each .nx2 file
    for url in nx2_urls:
        filename = os.path.basename(url)
        dest_path = os.path.join(temp_dir, filename)
        print(f"Downloading {url} -> {dest_path}")
        urllib.request.urlretrieve(url, dest_path)

    # 4. Rename files to include patch version (e.g., A01_VO.v49.nx2)
    for filepath in glob.glob(os.path.join(temp_dir, "*.nx2")):
        base = os.path.basename(filepath)
        # Insert .vXX. before the extension
        new_name = re.sub(r"\.nx2$", f".v{version}.nx2", base)
        new_path = os.path.join(temp_dir, new_name)
        os.rename(filepath, new_path)

    # 5. Copy to target directory
    for filepath in glob.glob(os.path.join(temp_dir, "*.nx2")):
        target_path = os.path.join(target_dir, os.path.basename(filepath))
        print(f"Copying {filepath} -> {target_path}")
        shutil.copy(filepath, target_path)
        # Set permissions like in your script
        os.chmod(target_path, 0o664)
        try:
            shutil.chown(target_path, group="www-data")
        except Exception:
            print(f"Warning: could not chown {target_path}, continue...")

    print("VO assets processed successfully.")


def main():
    mod = "coop"
    dryrun = os.getenv("DRY_RUN", "false").lower() in ("1", "true", "yes")
    version = os.getenv("PATCH_VERSION")

    if version is None:
        print('Please pass patch version in environment variable PATCH_VERSION')
        sys.exit(1)

    print(f"=== Starting mod updater for version {version}, dryrun={dryrun} ===")

    # /updater_{mod}_files will be appended by create_file
    target_dir = '/tmp/legacy-featured-mod-files'

    # Prepare git repo
    repo_dir = prepare_repo()

    # Download VO assets
    vo_dir = os.path.join(target_dir, f"updates_{mod}_files")
    download_vo_assets(version, vo_dir)

    # target filename / fileId in updates_{mod}_files table / source files with version placeholder
    # if source files is single string, file is copied directly
    # if source files is a list, files are zipped
    files = [
        ('init_coop.v{}.lua', 1, os.path.join(repo_dir, 'init_coop.lua')),
        ('lobby_coop_v{}.cop', 2, [
            os.path.join(repo_dir, 'lua'),
            os.path.join(repo_dir, 'mods'),
            os.path.join(repo_dir, 'units'),
            os.path.join(repo_dir, 'mod_info.lua'),
            os.path.join(repo_dir, 'readme.md'),
            os.path.join(repo_dir, 'changelog.md'),
        ]),
        ('A01_VO.v{}.nx2', 3, None),
        ('A02_VO.v{}.nx2', 4, None),
        ('A03_VO.v{}.nx2', 5, None),
        ('A04_VO.v{}.nx2', 6, None),
        ('A05_VO.v{}.nx2', 7, None),
        ('A06_VO.v{}.nx2', 8, None),
        ('C01_VO.v{}.nx2', 9, None),
        ('C02_VO.v{}.nx2', 10, None),
        ('C03_VO.v{}.nx2', 11, None),
        ('C04_VO.v{}.nx2', 12, None),
        ('C05_VO.v{}.nx2', 13, None),
        ('C06_VO.v{}.nx2', 14, None),
        ('E01_VO.v{}.nx2', 15, None),
        ('E02_VO.v{}.nx2', 16, None),
        ('E03_VO.v{}.nx2', 17, None),
        ('E04_VO.v{}.nx2', 18, None),
        ('E05_VO.v{}.nx2', 19, None),
        ('E06_VO.v{}.nx2', 20, None),
        ('Prothyon16_VO.v{}.nx2', 21, None),
        ('TCR_VO.v{}.nx2', 22, None),
        ('SCCA_Briefings.v{}.nx2', 23, None),
        ('SCCA_FMV.nx2.v{}.nx2', 24, None),
        ('FAF_Coop_Operation_Tight_Spot_VO.v{}.nx2', 25, None),
    ]

    conn = get_db_connection()
    try:
        do_files(conn, mod, version, files, target_dir, dryrun)
    finally:
        conn.close()

    print(f"=== Deployment finished for version {version}, dryrun={dryrun} ===")


if __name__ == "__main__":
    main()
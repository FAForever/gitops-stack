#!/usr/bin/python3
# -*- coding: utf-8 -*-

"""
clone: https://github.com/FAForever/faf-coop-maps

FAF coop maps updater

All default settings are setup for FAF production!
Override the directory settings for local testing.
To get more help run
    $ pipenv run patch-coop-maps -h

Default usage:
    $ pipenv run patch-coop-maps -s <git tag>
"""
import argparse
import hashlib
import logging
import os
import shutil
import subprocess
import sys
import zipfile
from tempfile import TemporaryDirectory
from typing import NamedTuple, List

import mysql.connector

logger: logging.Logger = logging.getLogger()
logger.setLevel(logging.DEBUG)

fixed_file_timestamp = 1078100502  # 2004-03-01T00:21:42Z


db_config = {
    "host": os.getenv("DATABASE_HOST", "localhost"),
    "user": os.getenv("DATABASE_USERNAME", "root"),
    "password": os.getenv("DATABASE_PASSWORD", "banana"),
    "database": os.getenv("DATABASE_NAME", "faf_lobby"),
}


def get_db_connection():
    """Create and return a MySQL connection."""
    try:
        conn = mysql.connector.connect(**db_config)
        if conn.is_connected():
            logger.debug(f"Connected to MySQL at {db_config['host']}")
            return conn
    except Error as e:
        logger.error(f"MySQL connection failed: {e}")
        sys.exit(1)


def run_sql(conn, sql: str) -> str:
    """
    Run an SQL query directly on the MySQL database instead of via Docker.
    Returns output in a string format similar to the old implementation.
    """
    logger.debug(f"Executing SQL query:\n{sql}")
    try:
        with conn.cursor() as cursor:
            cursor.execute(sql)

            # If it's a SELECT query, fetch and format results
            if sql.strip().lower().startswith("select"):
                rows = cursor.fetchall()
                column_names = [desc[0] for desc in cursor.description]
                # Simulate the Docker mysql CLI tabular text output
                lines = ["\t".join(column_names)]
                for row in rows:
                    lines.append("\t".join(str(x) for x in row))
                result = "\n".join(lines)
            else:
                conn.commit()
                result = "Query OK"

        logger.debug(f"SQL result:\n{result}")
        return result

    except Error as e:
        logger.error(f"SQL execution failed: {e}")
        sys.exit(1)


class CoopMap(NamedTuple):
    folder_name: str
    map_id: int
    map_type: int

    def build_zip_filename(self, version: int) -> str:
        return f"{self.folder_name.lower()}.v{version:04d}.zip"

    def build_folder_name(self, version: int) -> str:
        return f"{self.folder_name.lower()}.v{version:04d}"


# Coop maps are in db table `coop_map`
coop_maps: List[CoopMap] = [
    # Forged Alliance missions
    CoopMap("X1CA_Coop_001", 1, 0),
    CoopMap("X1CA_Coop_002", 3, 0),
    CoopMap("X1CA_Coop_003", 4, 0),
    CoopMap("X1CA_Coop_004", 5, 0),
    CoopMap("X1CA_Coop_005", 6, 0),
    CoopMap("X1CA_Coop_006", 7, 0),

    # Vanilla Aeon missions
    CoopMap("SCCA_Coop_A01", 8, 1),
    CoopMap("SCCA_Coop_A02", 9, 1),
    CoopMap("SCCA_Coop_A03", 10, 1),
    CoopMap("SCCA_Coop_A04", 11, 1),
    CoopMap("SCCA_Coop_A05", 12, 1),
    CoopMap("SCCA_Coop_A06", 13, 1),

    # Vanilla Cybran missions
    CoopMap("SCCA_Coop_R01", 20, 2),
    CoopMap("SCCA_Coop_R02", 21, 2),
    CoopMap("SCCA_Coop_R03", 22, 2),
    CoopMap("SCCA_Coop_R04", 23, 2),
    CoopMap("SCCA_Coop_R05", 24, 2),
    CoopMap("SCCA_Coop_R06", 25, 2),

    # Vanilla UEF missions
    CoopMap("SCCA_Coop_E01", 14, 3),
    CoopMap("SCCA_Coop_E02", 15, 3),
    CoopMap("SCCA_Coop_E03", 16, 3),
    CoopMap("SCCA_Coop_E04", 17, 3),
    CoopMap("SCCA_Coop_E05", 18, 3),
    CoopMap("SCCA_Coop_E06", 19, 3),

    # Custom missions
    CoopMap("FAF_Coop_Prothyon_16", 26, 4),
    CoopMap("FAF_Coop_Fort_Clarke_Assault", 27, 4),
    CoopMap("FAF_Coop_Theta_Civilian_Rescue", 28, 4),
    CoopMap("FAF_Coop_Novax_Station_Assault", 31, 4),
    CoopMap("FAF_Coop_Operation_Tha_Atha_Aez", 32, 4),
    CoopMap("FAF_Coop_Havens_Invasion", 33, 4),
    CoopMap("FAF_Coop_Operation_Rescue", 35, 4),
    CoopMap("FAF_Coop_Operation_Uhthe_Thuum_QAI", 36, 4),
    CoopMap("FAF_Coop_Operation_Yath_Aez", 37, 4),
    CoopMap("FAF_Coop_Operation_Ioz_Shavoh_Kael", 38, 4),
    CoopMap("FAF_Coop_Operation_Trident", 39, 4),
    CoopMap("FAF_Coop_Operation_Blockade", 40, 4),
    CoopMap("FAF_Coop_Operation_Golden_Crystals", 41, 4),
    CoopMap("FAF_Coop_Operation_Holy_Raid", 42, 4),
    CoopMap("FAF_Coop_Operation_Tight_Spot", 45, 4),
    CoopMap("FAF_Coop_Operation_Overlord_Surth_Velsok", 47, 4),
    CoopMap("FAF_Coop_Operation_Rebel's_Rest", 48, 4),
    CoopMap("FAF_Coop_Operation_Red_Revenge", 49, 4),
]

def fix_file_timestamps(files: List[str]) -> None:
    for file in files:
        logger.debug(f"Fixing timestamp in {file}")
        os.utime(file, (fixed_file_timestamp, fixed_file_timestamp))


def fix_folder_paths(folder_name: str, files: List[str], new_version: int) -> None:
    old_maps_lua_path = f"/maps/{folder_name}/"
    new_maps_lua_path = f"/maps/{folder_name.lower()}.v{new_version:04d}/"

    for file in files:
        logger.debug(f"Fixing lua folder path in {file}: '{old_maps_lua_path}' -> '{new_maps_lua_path}'")

        with open(file, "rb") as file_handler:
            data = file_handler.read()
            data = data.replace(old_maps_lua_path.encode(), new_maps_lua_path.encode())

        with open(file, "wb") as file_handler:
            file_handler.seek(0)
            file_handler.write(data)


def get_latest_map_version(coop_map: CoopMap) -> int:
    logger.debug(f"Fetching latest map version for coop map {coop_map}")

    query = f"""
        SELECT version FROM coop_map WHERE id = {coop_map.map_id};
    """
    result = run_sql(query).split("\n")
    assert len(result) == 3, f"Mysql returned wrong result! Either map id {coop_map.map_id} is not in table coop_map" \
                             f" or the where clause is wrong. Result: " + "\n".join(result)
    return int(result[1])


def new_file_is_different(old_file_name: str, new_file_name: str) -> bool:
    old_file_md5 = calc_md5(old_file_name)
    new_file_md5 = calc_md5(new_file_name)

    logger.debug(f"MD5 hash of {old_file_name} is: {old_file_md5}")
    logger.debug(f"MD5 hash of {new_file_name} is: {new_file_md5}")

    return old_file_md5 != new_file_md5


def update_database(conn, coop_map: CoopMap, new_version: int) -> None:
    logger.debug(f"Updating coop map {coop_map} in database to version {new_version}")

    query = f"""
        UPDATE coop_map
        SET version = {new_version}, filename = "maps/{coop_map.build_zip_filename(new_version)}"
        WHERE id = {coop_map.map_id}
    """
    run_sql(conn, query)


def copytree(src, dst, symlinks=False, ignore=None):
    """
    Reason for that method is because shutil.copytree will raise exception on existing
    temporary directory
    """

    for item in os.listdir(src):
        s = os.path.join(src, item)
        d = os.path.join(dst, item)
        if os.path.isdir(s):
            shutil.copytree(s, d, symlinks, ignore)
        else:
            shutil.copy2(s, d)


def create_zip_package(coop_map: CoopMap, version: int, files: List[str], tmp_folder_path: str, zip_file_path: str):
    fix_folder_paths(coop_map.folder_name, files, version)
    fix_file_timestamps(files)
    with zipfile.ZipFile(zip_file_path, 'w', zipfile.ZIP_BZIP2) as zip_file:
        for path in files:
            zip_file.write(path, arcname=f"/{coop_map.build_folder_name(version)}/{os.path.relpath(path, tmp_folder_path)}")


def process_coop_map(conn, coop_map: CoopMap, simulate: bool, git_directory:str, coop_maps_path: str):
    logger.info(f"Processing: {coop_map}")

    temp_dir = TemporaryDirectory()
    copytree(os.path.join(git_directory, coop_map.folder_name), temp_dir.name)
    processing_files = []
    for root, dirs, files in os.walk(temp_dir.name):
        for f in files:
            processing_files.append(os.path.relpath(os.path.join(root, f), temp_dir.name))

    logger.debug(f"Files to process in {coop_map}: {processing_files}")
    current_version = get_latest_map_version(coop_map)
    current_file_path = os.path.join(coop_maps_path, coop_map.build_zip_filename(current_version))
    zip_file_path = os.path.join(temp_dir.name, coop_map.build_zip_filename(current_version))
    create_zip_package(coop_map, current_version, processing_files, temp_dir.name, zip_file_path)
    if current_version == 0 or new_file_is_different(current_file_path, zip_file_path):
        new_version = current_version + 1

        if current_version == 0:
            logger.info(f"{coop_map} first upload. New version: {new_version}")
        else:
            logger.info(f"{coop_map} has changed. New version: {new_version}")

        if not simulate:
            temp_dir.cleanup()
            temp_dir = TemporaryDirectory()
            copytree(os.path.join(git_directory, coop_map.folder_name), temp_dir.name)

            zip_file_path = os.path.join(coop_maps_path, coop_map.build_zip_filename(new_version))
            create_zip_package(coop_map, new_version, processing_files, temp_dir.name, zip_file_path)

            update_database(conn, coop_map, new_version)
        else:
            logger.info(f"Updating database skipped due to simulation")
    else:
        logger.info(f"{coop_map} remains unchanged")
    temp_dir.cleanup()


def calc_md5(filename: str) -> str:
    """
    Calculate the MD5 hash of a file
    """
    hash_md5 = hashlib.md5()
    with open(filename, "rb") as f:
        for chunk in iter(lambda: f.read(4096), b""):
            hash_md5.update(chunk)
    return hash_md5.hexdigest()


def run_checked_shell(cmd: List[str]) -> subprocess.CompletedProcess:
    """
    Runs a command as a shell process and checks for success
    Output is captured in the result object
    :param cmd: command to run
    :return: CompletedProcess of the execution
    """
    logger.debug("Run shell command: {cmd}".format(cmd=cmd))
    return subprocess.run(cmd, check=True, stdout=subprocess.PIPE)


def git_checkout(path: str, tag: str) -> None:
    """
    Checkout a git tag of the git repository. This requires the repo to be checked out in the path folder!

    :param path: the path of the git repository to checkout
    :param tag: version of the git tag (full name)
    :return: nothing
    """
    cwd = os.getcwd()
    os.chdir(path)
    logger.debug(f"Git checkout from path {path}")

    try:
        run_checked_shell(["git", "fetch"])
        run_checked_shell(["git", "checkout", tag])
    except subprocess.CalledProcessError as e:
        logger.error(f"git checkout failed - please check the error message: {e.stderr}")
        exit(1)
    finally:
        os.chdir(cwd)


def create_zip(content: List[str], relative_to: str, output_file: str) -> None:
    logger.debug(f"Zipping files to file `{output_file}`: {content}")

    with zipfile.ZipFile(output_file, 'w', zipfile.ZIP_DEFLATED) as zip_file:
        for path in content:
            if os.path.isdir(path):
                cwd = os.getcwd()
                os.chdir(path)

                for root, dirs, files in os.walk(path):
                    for next_file in files:
                        file_path = os.path.join(root, next_file)
                        zip_file.write(file_path, os.path.relpath(file_path, relative_to))

                os.chdir(cwd)
            else:
                zip_file.write(path, os.path.relpath(path, relative_to))


if __name__ == "__main__":
    # Setting up logger
    stream_handler = logging.StreamHandler(sys.stdout)
    stream_handler.setFormatter(logging.Formatter('%(levelname)-5s - %(message)s'))
    logger.addHandler(stream_handler)

    # Setting up CLI arguments
    parser = argparse.ArgumentParser(description=__doc__)

    parser.add_argument("version", help="the git tag name of the version")
    parser.add_argument("-s", "--simulate", dest="simulate", action="store_true", default=False,
                        help="only runs a simulation without updating the database")
    parser.add_argument("--git-directory", dest="git_directory", action="store",
                        default="/opt/featured-mods/faf-coop-maps",
                        help="base directory of the faf-coop-maps repository")
    parser.add_argument("--maps-directory", dest="coop_maps_path", action="store",
                        default="/opt/faf/data/maps",
                        help="directory of the coop map files (content server)")

    args = parser.parse_args()

    git_checkout(args.git_directory, args.version)
    conn = get_db_connection()

    for coop_map in coop_maps:
        try:
            process_coop_map(conn, coop_map, args.simulate, args.git_directory, args.coop_maps_path)
        except Exception as error:
            logger.warning(f"Unable to parse {coop_map}", exc_info=True)

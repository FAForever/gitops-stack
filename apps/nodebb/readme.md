# How to setup NodeBB for FAF

## 1. Setup MongoDB
After setting up the secrets, run
`scripts/init-mongodb.sh`. This will setup MongoDB and create a user with the configured password.


## 2. Install NodeBB

### 2.1. Configuration
You need to add the config.json as a whole secret.

Example format:

```json
{
    "url": "https://forum.faforever.com",
    "secret": "banana",
    "database": "mongo",
    "port": 4567,
    "mongo": {
        "host": "mongodb",
        "port": "27017",
        "username": "nodebb",
        "password": "banana",
        "database": "nodebb"
    },
  "oauth": {
    "authorizationURL": "https://hydra.faforever.com/oauth2/auth",
    "tokenURL": "https://hydra.faforever.com/oauth2/token",
    "fafApiProfileURL": "https://api.faforever.com/me",
    "id": "faf-forum",
    "secret": "banana",
    "scope": "public_profile lobby"
  }
}
```


### 2.2. On first run

Due to technical limitations of how plugins are installed, we mount the installed npm packges by mounting the `node_modules` folder into a pvc.

On first run NodeBB you will need to enter a shell on the container and install the packages once, as well as our sso plugin.

```sh
npm i  && npm i nodebb-plugin-sso-oauth-faforever
```

Also you will probably need to run through an installer, however that looks like today.

After that make sure to write down the credentials of the admin user. You will need it later to configure NodeBB.



### 2.3. Install
Start the installation via the web installer. 


## 3. Setup SSO

### 3.1. Activate the plugin
After the OAuth login NodeBB needs to fetch the user relevant information from a dedicated website like `/me`. As this
is different for each provider we published our own plugin on npm.

Assuming you already installed it in 2.2, you just go to the plugins section in the admin panel and activate it.

Find the plugin `nodebb-plugin-sso-oauth-faforever`, then activate it from the Inactive tab and finally
restart NodeBB.

### 3.2. Configure the faf-db
The nodebb oauth login requires a row in the `oauth_clients` table. Take the `id` and `secret` from the config.json.
The client_type needs to be `confidential`, the scope `public_profile`, the callback url
`${NODEBB_URL}/auth/${NODEBB_OAUTH_ID}/callback`.


### 3.3. Configure SSO
If you ever fuck up the SSO and can't see a login form because it was disabled: append `?local=1` to the `/login` page. This way you can always login as the default admin user. 

In the ACP under `Settings->User` configure:
-  Disable username changes
-  Disable email changes
-  Disable pass changes
-  Disable Allow account deletion
-  Registration Type = No registration

In the ACP under `Manage->Privileges` configure:
- Disable Local Login for all groups

If you configured it correctly, on Login you should be redirected to the faf-java-api login page instantly.


## 4. Setup write-api access
The SSO plugin allows FAF users to login with their current username. But what happens if the FAF user changes his
username? To cover that case we need to setup the `write-api` which is now a core of NodeBB. This enables our API to also change the
username in the NodeBB account.

Go the admin panel of NodeBB and `Settings->API Access`. Generate a master token with user id 0.

Configure the faf-java-api with this token.

---------

If you want to test it manually, you can use postman. The master token is your value for the Authorization header.
Use it like `Authorization: Bearer <master-token>`.

In this sample call we impersonate the admin user (id 1) to change the name to admin2.

PUT: `http://localhost:4567/api/v3/users/1`
```
{
   	"_uid": "1",
   	"username": "admin2"
}
```
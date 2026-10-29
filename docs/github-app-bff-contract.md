# GitHub App connection: Web BFF contract

This stage implements the Project Service side of P-09 through P-11. GitHub App
authorization is for repository access; it is not DevField login. The GitHub App
setting **Request user authorization during installation** must be disabled so
that the explicit state and PKCE flow is used.

## Configuration

Register `PROJECT_GITHUB_APP_CALLBACK_URL` as the GitHub App user authorization
callback and `PROJECT_GITHUB_APP_SETUP_URL` as its post-installation Setup URL.
Both URLs terminate at the Web BFF. The callback URL sent as `redirect_uri` in
P-09 and in the server-side code exchange is the same configured string.
`PROJECT_GITHUB_APP_INSTALL_URL` is the app's GitHub installation URL. HTTPS is
required outside local loopback development. Configure expiring GitHub App user
tokens. Keep the app client secret and AES-256-GCM key in the server environment.
The app private key path is reserved for the later App JWT / installation-token
path; P-09 through P-11 use the GitHub App **user** token endpoints and do not
read the private key.

## BFF sequence

1. The BFF calls `POST /api/github/connections/start` with the current DevField
   access JWT. Project Service verifies JWT/JWKS and AUTH-15 and returns
   `{nextAction,url,proof,expiresAt}`. The BFF keeps `proof` in its server-side
   DevField session, scoped to this connection attempt and expiry, then sends
   the browser to `url`. For `AUTHORIZE_USER`, `proof` is the OAuth `state`.
   For `INSTALL_APP`, GitHub does not echo a state on Setup Redirect, so the
   BFF retrieves the proof from its own session record.
2. At the BFF's user authorization GET callback, compare the returned `state`
   to the pending session proof. With the **same** DevField user and `sid`, call
   `POST /api/github/connections/callback/complete` with `{code,proof}` and a
   current DevField access JWT. The Project Service consumes the attempt once,
   exchanges the code using its stored PKCE verifier, confirms `/user`, and
   persists encrypted tokens. Never put the code or token in a browser URL
   after handling the callback.
3. At the BFF's installation GET Setup URL, retrieve the pending install proof
   from that session. With the same user and `sid`, call
   `POST /api/github/connections/setup/complete` with
   `{proof,installationId,setupAction}` and the current DevField access JWT.
   Accepted actions are `install` and `update`. The supplied ID only becomes
   connected after Project Service verifies the GitHub user, accessible App
   installation, and repository endpoint. `PENDING_APPROVAL` means the user
   token cannot currently confirm that installation; it grants no access.
4. Clear the BFF's pending proof after either completion or expiry. Render a
   generic failure on errors; do not display GitHub's code or tokens.

The two completion POSTs are server-to-server paths. Browser GET callbacks are
owned by the BFF and never require a bearer header from the browser. The
Project Service derives the user and `sid` only from the verified JWT and
checks AUTH-15 on every completion request. The request body cannot select a
different user or session. A consumed or expired proof cannot be retried.

`GET /api/github/connections` returns `{userAuthorization,installation}`.
`GET /api/github/repositories?page=1&perPage=30` returns
`{status,repositories,page,hasNext}`. Status is one of `CONNECTED`,
`USER_AUTHORIZATION_REQUIRED`, `INSTALLATION_REQUIRED`, or
`INSTALLATION_PENDING`. Repository entries contain `id`, `fullName`, `name`,
and `private`. The list is scoped to the authenticated user and GitHub's
user-accessible repositories within the verified App installation.

The BFF should show separate user authorization and installation approval
states. A fresh installation attempt is required after an approval-pending
result. P-12/P-13 consent, disconnect, webhook revocation, and Private project
registration follow in later stages. Actual GitHub browser E2E must run after
the BFF is implemented.

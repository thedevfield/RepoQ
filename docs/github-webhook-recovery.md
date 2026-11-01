# GitHub App Webhook failure recovery

Private registration remains disabled by default. The Project Service accepts signed GitHub App deliveries at `POST /api/github/webhook`. A successful delivery has one `project.github_webhook_delivery` row keyed by `X-GitHub-Delivery`; raw payloads are not stored.

Configure `PROJECT_GITHUB_WEBHOOK_SECRET` in the Project Service runtime before connecting GitHub's Webhook URL. A missing secret rejects every delivery. Do not place the value in `.env.example` or logs.

1. In the GitHub App settings, open **Advanced → Recent Deliveries**. Identify failed deliveries by delivery ID, event, action, time, and response code. Do not copy payloads, signatures, tokens, or secrets into tickets or logs.
2. Check Project Service logs and database availability for the matching time. Query the delivery table by ID. No row means processing did not commit; a row means the access change and delivery record committed together. A matching ID with a different payload hash is rejected.
3. After fixing the cause, use GitHub's **Redeliver** action for the failed delivery. The same ID and payload is safe to resend; duplicate deliveries do not repeat the access change. GitHub does not automatically redeliver failed deliveries.
4. Verify the delivery now succeeds, its ID appears exactly once in `project.github_webhook_delivery`, affected Private `project_repository.access_state` is `BLOCKED`, and active affected `analysis_job` rows are `CANCELLED`. Confirm Public repositories remain usable.
5. If manual delivery volume becomes operationally significant, a later tool may use GitHub's delivery API after checking the App's current permissions. This implementation does not add permissions or automatic redelivery.

Webhook delivery can be missed. Every Private metadata or code access must still check the latest consent and current GitHub user and App repository permissions. A GitHub API failure denies Private access. A `CANCELLED` DB job means its result cannot be published; there is no worker or Snapshot cleanup in this release. Any future worker must check project version, job state, and permission at its final write fence.

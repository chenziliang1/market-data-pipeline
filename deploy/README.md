# Deploying to EC2 (CD)

`.github/workflows/ci.yml` has three jobs:

```text
push -> test (unit tests + Testcontainers integration tests)
          -> image  (build the image, push it to ghcr.io tagged sha-<commit> and the branch name)
               -> deploy (through AWS Systems Manager, switch EC2 to this commit's image blue-green style)
```

`test` and `image` run on every push. `deploy` is off by default; it runs only after the one-time setup below and with `DEPLOY_ENABLED=true`.

Deployment needs no SSH:
- GitHub obtains a short-lived AWS role through OIDC; no AWS keys are stored in the repository.
- Commands reach EC2 through Systems Manager; no inbound port has to be open.
- The database password lives only in `/opt/tradedate/.env` on EC2 and never enters GitHub.

## What a deployment looks like

- EC2 runs Kafka, Redis, nginx and one of the two application slots `app-blue` and `app-green` from `/opt/tradedate/docker-compose.yml`, which the deploy job copies from `deploy/docker-compose.ec2.yml`.
- **Blue-green, no downtime**: the new image starts in the idle slot (blue on `127.0.0.1:8081`, green on `127.0.0.1:8082`). Only after its own `/actuator/health` passes is nginx switched (`nginx -s reload`, which lets in-flight connections finish); five seconds later the old slot is stopped gracefully (Spring Boot's `server.shutdown=graceful` finishes the requests it holds). The active slot is whatever `/opt/tradedate/nginx/upstream.conf` points nginx at; nothing else records it, so the two cannot disagree.
- **A failed deploy does not touch production**: if the new slot is not healthy within three minutes, the job fails and prints that slot's logs; the old slot keeps serving and `.env` is unchanged. The health check returns 503 when the application cannot reach PostgreSQL or Redis, which also counts as a failure.
- Only nginx listens, on `127.0.0.1:8080`; nothing is reachable from the internet. Use an SSH tunnel to reach the API (see the last section).
- The load endpoint and `/api/admin/**` require `X-API-Key`. The key is generated on EC2 during the first deploy and written to `APP_API_KEY` in `.env`; it never passes through GitHub.
- Kafka data lives in a Docker volume, so a redeploy loses neither topics nor committed offsets.
- The database schema is managed by the Flyway migrations that run at application start, so deploying a new version applies them automatically.
- Memory: about 500 MiB at rest (application about 200, Kafka about 250, nginx and Redis little). Both application slots run for a few seconds during a switch, so a 1 GiB t3.micro needs 2 GiB of swap (step 2).

## One-time setup

Prerequisites: EC2 and RDS are in the same VPC and the RDS security group already allows this EC2 instance on 5432 (it already serves as the SSH bastion, so this should hold). The instance is x86 (t3 family).

Values in `<...>` below come from the AWS console; do not commit them.

### 1. Give EC2 a role that can receive Systems Manager commands

1. IAM → Roles → Create role → trusted entity "AWS service", use case "EC2".
2. Attach the `AmazonSSMManagedInstanceCore` policy; name the role, for example, `tradedate-ec2-ssm`.
3. EC2 console → select the instance → Actions → Security → Modify IAM role → pick that role. If the instance already has a role, add `AmazonSSMManagedInstanceCore` to it instead.
4. Wait a few minutes, then check Systems Manager → Fleet Manager: the instance must show as Online. Amazon Linux ships the SSM Agent; nothing to install.

### 2. Install Docker on EC2, add swap, write the database settings

Log in over SSH (Windows PowerShell):

```powershell
ssh -i <private key path> ec2-user@<EC2 public IP>
```

Check the OS version first: `cat /etc/os-release`. The commands below are for Amazon Linux 2023; on Amazon Linux 2 replace `dnf` with `yum`.

```bash
# Docker and the Compose plugin
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -fsSL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
sudo docker compose version

# 2 GiB of swap that survives a reboot
sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab
free -h
```

Write the database settings with an editor, not with `echo`, so the password does not end up in the shell history:

```bash
sudo mkdir -p /opt/tradedate
sudo dnf install -y nano
sudo nano /opt/tradedate/.env
```

Contents:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://<RDS endpoint>:5432/postgres
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=<database password>
```

After saving:

```bash
sudo chmod 600 /opt/tradedate/.env
```

The deploy job maintains an `IMAGE=...` line at the end of this file that records the deployed image; there is no need to edit it by hand.

### 3. Let GitHub Actions assume an AWS role (OIDC)

1. IAM → Identity providers → Add provider → OpenID Connect.
   - Provider URL: `https://token.actions.githubusercontent.com`
   - Audience: `sts.amazonaws.com`
2. IAM → Roles → Create role → trusted entity "Web identity".
   - Identity provider: `token.actions.githubusercontent.com`, audience: `sts.amazonaws.com`
   - GitHub organization: `chenziliang1`, repository: `market-data-pipeline`, branch: `main`
   - Add no permissions yet; name the role, for example, `tradedate-github-deploy`.
3. Open the role → Trust relationships and make sure the condition contains these two lines, which restrict it to this repository and branch:

   ```json
   "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
   "token.actions.githubusercontent.com:sub": "repo:chenziliang1@<user ID>/market-data-pipeline@<repository ID>:ref:refs/heads/main"
   ```

   Newer repositories include the user ID and the repository ID in `sub` by default, so a same-named repository created after this one is deleted cannot assume the role. The prefix a repository actually uses can be read with:

   ```bash
   gh api repos/chenziliang1/market-data-pipeline/actions/oidc/customization/sub --jq .sub_claim_prefix
   ```

4. Permissions → Add permissions → Create inline policy → JSON, allowing commands to this one instance only:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Action": "ssm:SendCommand",
         "Resource": [
           "arn:aws:ec2:us-east-2:<AWS account ID>:instance/<EC2 instance ID>",
           "arn:aws:ssm:us-east-2::document/AWS-RunShellScript"
         ]
       },
       {
         "Effect": "Allow",
         "Action": "ssm:GetCommandInvocation",
         "Resource": "*"
       }
     ]
   }
   ```

### 4. Set the repository variables and secrets on GitHub

Repository → Settings → Secrets and variables → Actions.

**Variables** tab → New repository variable:

| Name | Value |
| :--- | :--- |
| `AWS_REGION` | `us-east-2` |
| `DEPLOY_BRANCH` | `main` |
| `DEPLOY_ENABLED` | `true` |

**Secrets** tab → New repository secret. These two are not secrets as such; they are stored as secrets so that logs show them as `***` and a public repository does not expose the account ID and the instance ID:

| Name | Value |
| :--- | :--- |
| `AWS_DEPLOY_ROLE_ARN` | ARN of the role from step 3 |
| `EC2_INSTANCE_ID` | The EC2 instance ID |

### 5. Trigger a deploy and verify it

1. Push a commit to `main`, or open the latest CI run under Actions → Re-run all jobs.
2. The `deploy` job turns green and its log shows `Active slot: ..., deploying ... to <slot>`, `{"status":"UP"}` and `Deployed ghcr.io/chenziliang1/market-data-pipeline:sha-... to <slot>`.
3. Confirm on EC2:

   ```bash
   cd /opt/tradedate
   sudo docker compose ps
   curl -s http://127.0.0.1:8080/actuator/health
   ```

## Day-to-day operations

- **Rollback**: under Actions, open an earlier successful CI run and re-run only its `deploy` job. It deploys that commit's image to the idle slot and switches over, again without downtime.
- **Logs**: on EC2, `cd /opt/tradedate && cat nginx/upstream.conf` shows the active slot; then `sudo docker compose logs --tail 100 app-blue` (or `app-green`).
- **API key**: on EC2, `sudo grep APP_API_KEY /opt/tradedate/.env`. Send it in the `X-API-Key` header.
- **Dead letters**: through the tunnel, `GET /api/admin/dead-letters` lists the failed messages and their reasons; after fixing the cause, `POST /api/admin/dead-letters/replay` replays them.
- **Reaching the API from your machine**: open an SSH tunnel, then use `http://localhost:18080`:

  ```powershell
  ssh -i <private key path> -N -L 18080:127.0.0.1:8080 ec2-user@<EC2 public IP>
  ```

- **Stopping to save money**: set `DEPLOY_ENABLED` to `false` first, then stop EC2 and RDS. Otherwise every later push fails its deploy because the instance is unreachable.

## Troubleshooting

| Symptom | Cause |
| :--- | :--- |
| `Could not assume role` / `Not authorized to perform sts:AssumeRoleWithWebIdentity` | The repository or branch in the step 3 trust relationship does not match the real one |
| `InvalidInstanceId` | The instance is not Online in Fleet Manager: check the step 1 role and whether the instance can reach the internet |
| `.env is missing` | The database settings of step 2 were never written |
| Health check not passed within three minutes | The old slot is still serving. Read the new slot's logs printed by the job: usually wrong connection settings in `.env`, an RDS security group that does not allow this instance, or a failed Flyway migration |
| Containers restart repeatedly and `dmesg` shows `Out of memory` | Swap is missing; check with `free -h` |

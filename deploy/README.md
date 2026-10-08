# 部署到 EC2 (CD)

`.github/workflows/ci.yml` 里有三个 job:

```text
push -> test (单元测试 + Testcontainers 集成测试)
          -> image  (构建镜像, 推到 ghcr.io, 标签为 sha-<commit> 和分支名)
               -> deploy (通过 AWS Systems Manager 让 EC2 用蓝绿方式切换到这个 commit 的镜像)
```

`test` 和 `image` 每次 push 都会运行。`deploy` 默认关闭, 完成下面的一次性配置, 并设置 `DEPLOY_ENABLED=true` 后才会运行。

部署不需要 SSH:
- GitHub 通过 OIDC 换取一个短期的 AWS 角色, 仓库里不存 AWS 密钥。
- 命令通过 Systems Manager 发到 EC2, 不需要开放入站端口。
- 数据库密码只放在 EC2 上的 `/opt/tradedate/.env` 里, 不进 GitHub。

## 部署后的样子

- EC2 上用 `/opt/tradedate/docker-compose.yml` 运行 Kafka, Redis, nginx, 以及两个应用槽位 `app-blue` 和 `app-green` 中的一个。这个文件由部署任务从 `deploy/docker-compose.ec2.yml` 复制过去。
- **蓝绿部署, 不停机**: 新镜像先在空闲的槽位启动 (blue 在 `127.0.0.1:8081`, green 在 `127.0.0.1:8082`), 它自己的 `/actuator/health` 通过后, 才把 nginx 切过去 (`nginx -s reload`, 旧连接处理完才退出), 等 5 秒再优雅停止旧槽位 (Spring Boot 的 `server.shutdown=graceful` 会处理完手上的请求)。当前槽位记在 `/opt/tradedate/active-slot`。
- **部署失败不影响线上**: 新槽位 3 分钟内没通过健康检查, 部署任务失败并打印它的日志, 旧槽位继续服务, `.env` 也不变。应用连不上 PostgreSQL 或 Redis 时健康检查返回 503, 也算失败。
- 对外只有 nginx 监听 `127.0.0.1:8080`, 外网访问不到。要访问就走 SSH 隧道 (见最后一节)。
- 拉取接口和 `/api/admin/**` 需要 `X-API-Key`。密钥在第一次部署时于 EC2 上生成, 写在 `.env` 的 `APP_API_KEY`, 不经过 GitHub。
- Kafka 的数据放在 Docker 卷里, 重新部署不会丢失 topic 和已提交的 offset。
- 数据库结构由应用启动时的 Flyway 迁移管理, 部署新版本时自动执行。
- 内存: 平时约 500 MiB (应用约 200, Kafka 约 250, nginx 和 Redis 很少); 切换的几秒内两个应用同时运行, 所以 1 GiB 的 t3.micro 需要加 2 GiB swap (第 2 步)。

## 一次性配置

前提: EC2 和 RDS 在同一个 VPC, 并且 RDS 的安全组已经允许这台 EC2 访问 5432 (它现在作为 SSH 跳板, 应该已经满足)。EC2 是 x86 实例 (t3 系列)。

下面 `<...>` 里的值从 AWS 控制台复制, 不要提交到仓库。

### 1. 给 EC2 一个能接收 Systems Manager 命令的角色

1. IAM → 角色 → 创建角色 → 可信实体选 "AWS 服务", 用例选 "EC2"。
2. 权限策略勾选 `AmazonSSMManagedInstanceCore`, 角色名例如 `tradedate-ec2-ssm`。
3. EC2 控制台 → 选中实例 → 操作 → 安全 → 修改 IAM 角色 → 选这个角色。如果实例已经有角色, 就把 `AmazonSSMManagedInstanceCore` 加到现有角色上。
4. 等几分钟, 到 Systems Manager → Fleet Manager, 看到这台实例状态为 Online 才算成功。Amazon Linux 自带 SSM Agent, 不用另装。

### 2. 在 EC2 上装 Docker, 加 swap, 写数据库配置

用 SSH 登录 EC2 (Windows PowerShell):

```powershell
ssh -i <私钥路径> ec2-user@<EC2 公网 IP>
```

先确认系统版本: `cat /etc/os-release`。下面是 Amazon Linux 2023 的命令; Amazon Linux 2 把 `dnf` 换成 `yum`。

```bash
# Docker 和 Compose 插件
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -fsSL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
sudo docker compose version

# 2 GiB swap, 重启后仍然生效
sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab
free -h
```

写数据库配置。用编辑器写, 不要用 `echo`, 免得密码留在 shell 历史里:

```bash
sudo mkdir -p /opt/tradedate
sudo dnf install -y nano
sudo nano /opt/tradedate/.env
```

内容:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://<RDS 端点>:5432/postgres
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=<数据库密码>
```

保存后:

```bash
sudo chmod 600 /opt/tradedate/.env
```

部署任务会在这个文件末尾维护一行 `IMAGE=...`, 记录当前部署的镜像, 不用手动改。

### 3. 让 GitHub Actions 能扮演一个 AWS 角色 (OIDC)

1. IAM → 身份提供商 → 添加提供商 → OpenID Connect。
   - 提供商 URL: `https://token.actions.githubusercontent.com`
   - 受众: `sts.amazonaws.com`
2. IAM → 角色 → 创建角色 → 可信实体选 "Web 身份"。
   - 身份提供商: `token.actions.githubusercontent.com`, 受众: `sts.amazonaws.com`
   - GitHub 组织: `chenziliang1`, 仓库: `Tradedate`, 分支: `fix/pipeline-correctness-and-reliability` (以后合并到 main, 就改成 `main`)
   - 先不加权限策略, 角色名例如 `tradedate-github-deploy`。
3. 打开这个角色 → 信任关系, 确认条件里有下面两行, 只允许这个仓库的这个分支:

   ```json
   "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
   "token.actions.githubusercontent.com:sub": "repo:chenziliang1/Tradedate:ref:refs/heads/fix/pipeline-correctness-and-reliability"
   ```

4. 权限 → 添加权限 → 创建内联策略 → JSON, 只允许向这一台实例发命令:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Action": "ssm:SendCommand",
         "Resource": [
           "arn:aws:ec2:us-east-2:<AWS 账号 ID>:instance/<EC2 实例 ID>",
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

### 4. 在 GitHub 上设置仓库变量

仓库 → Settings → Secrets and variables → Actions → **Variables** 标签页 → New repository variable。这些都不是密钥, 用 Variables 即可:

| 名称 | 值 |
| :--- | :--- |
| `AWS_REGION` | `us-east-2` |
| `AWS_DEPLOY_ROLE_ARN` | 第 3 步角色的 ARN |
| `EC2_INSTANCE_ID` | EC2 实例 ID |
| `DEPLOY_BRANCH` | `fix/pipeline-correctness-and-reliability` |
| `DEPLOY_ENABLED` | `true` |

### 5. 触发一次部署并验证

1. 往这个分支 push 一个 commit; 或者在 Actions 里打开最近一次 CI → Re-run all jobs。
2. `deploy` job 变绿, 日志里出现 `Active slot: ..., deploying ... to <槽位>`, `{"status":"UP"}` 和 `Deployed ghcr.io/chenziliang1/tradedate:sha-... to <槽位>`, 就说明部署成功了。
3. 在 EC2 上确认:

   ```bash
   cd /opt/tradedate
   sudo docker compose ps
   curl -s http://127.0.0.1:8080/actuator/health
   ```

## 日常操作

- **回滚**: 在 Actions 里打开一次更早的、成功的 CI 运行, 只重跑它的 `deploy` job。它会把那次 commit 的镜像部署到空闲槽位并切换过去, 同样不停机。
- **看日志**: 在 EC2 上 `cd /opt/tradedate && sudo docker compose logs --tail 100 app-$(cat active-slot)`。
- **查看 API 密钥**: 在 EC2 上 `sudo grep APP_API_KEY /opt/tradedate/.env`。调用时放在请求头 `X-API-Key` 里。
- **处理死信**: 通过隧道调用 `GET /api/admin/dead-letters` 查看失败的消息和原因, 原因修好后 `POST /api/admin/dead-letters/replay` 重放。
- **从自己电脑访问 API**: 开一个 SSH 隧道, 然后访问 `http://localhost:18080`:

  ```powershell
  ssh -i <私钥路径> -N -L 18080:127.0.0.1:8080 ec2-user@<EC2 公网 IP>
  ```

- **停机省钱**: 先把 `DEPLOY_ENABLED` 改成 `false`, 再停 EC2 和 RDS。否则之后的每次 push 都会因为连不上实例而部署失败。

## 常见问题

| 现象 | 原因 |
| :--- | :--- |
| `Could not assume role` / `Not authorized to perform sts:AssumeRoleWithWebIdentity` | 第 3 步信任关系里的仓库名或分支名和实际不一致 |
| `InvalidInstanceId` | 实例没在 Fleet Manager 里 Online: 检查第 1 步的角色, 以及实例能否访问外网 |
| `.env is missing` | 没做第 2 步的数据库配置 |
| 3 分钟内健康检查没通过 | 旧槽位仍在服务。看任务日志里打印的新槽位日志: 多半是 `.env` 的连接信息不对, RDS 安全组不允许这台 EC2, 或者 Flyway 迁移失败 |
| 容器反复重启, `dmesg` 里有 `Out of memory` | swap 没加上, 用 `free -h` 检查 |

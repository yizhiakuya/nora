# megumin 独立盘启用与系统盘清理

## 当前状态与范围

2026-10-10 实测：系统盘 `/dev/sda2` 为 ext4，约 114 GiB，可用约 1.1 GiB；独立 SSD `/dev/sdb1` 约 234 GiB，UUID `313fa5ae-8e9b-4e6e-bbcf-e83c6223b8e9`，已在 fstab 配置挂载 `/mnt/data`，仅使用约 4.5 MiB。

主要占用在 `/var/lib/containerd`（约 47 GiB）与 `/var/lib/docker`（不含运行时 overlay 挂载的约 3.2 GiB）。Docker 29.2.1 使用 containerd 2.2.1 的 overlayfs 镜像存储；仅迁移 Docker 目录不能解决镜像占用。现有 52 个容器、29 个运行中，包含 Nora、Sub2API、交易 bot 和数据库。

## 最小方案

1. 确认目标 SSD、持久挂载、文件系统与硬件健康，在线预复制两个存储目录至 `/mnt/data/docker`、`/mnt/data/containerd`，保留 UID/GID、权限、硬链接、ACL、扩展属性，跳过运行时子挂载。
2. 保存原 fstab、Docker/containerd 配置和服务配置，以及容器、镜像、数据卷清单。切换前确认全体 Docker 业务的停机窗口。
3. 停止原先运行的容器，在窗口内临时 runtime-mask Docker、Docker socket 和 containerd，防止管理程序重新拉起运行时；停止运行时后做最终同步与完整校验。将目标目录 bind mount 至原路径，配置验证通过后解除临时 mask 并恢复服务。fstab 使用 UUID 挂载独立盘、配置 bind mount 对该盘的依赖；服务增加挂载依赖与挂载点断言，防止缺盘时写回系统盘。
4. 启动运行时，恢复原先运行的容器，验证容器/镜像/数据卷清单与业务健康。原本停止的容器保持停止，应用配置和数据库 schema 不变。
5. 通过验证后才回收系统盘上已校验的旧存储副本；迁移配置与验证记录保存在独立盘，写清回滚步骤。清理仅针对可重建缓存，不删除不明文件、用户数据卷或未核实的历史数据。

保持 `/var/lib/docker`、`/var/lib/containerd` 原路径，避免修改容器挂载定义、第三方管理器或运行时内的绝对引用。独立盘使用现有 ext4 分区，不需要格式化或重新分区。

## 验证与风险边界

准备阶段不停止服务；最终同步和切换会暂停所有 Docker 业务，必须在数据准备完成后确认。源目录的删除需要目标挂载核实、同步校验、容器与业务验收全部成功；失败先回到原目录恢复服务。记录实际停机时间、清理前后两个文件系统的使用量、服务状态，以及未做的验证。

首次校验发现 containerd 元数据与待删除快照目录有差异，自动回滚恢复了原来的全部业务。journal 显示运行时在停止约 53 秒后被重新启动，因此停机冻结必须包含临时禁止外部拉起的保护。该保护只使用 `/run/systemd/system`，恢复时解除，不修改原有 enable/restart 策略。

## 实施结果

2026-10-10 13:56:20（Asia/Shanghai）完成切换，14:00:46 完成旧副本清理。独立盘原本已经挂载，但 Docker/containerd 默认存储仍在系统盘，这是独立盘长期闲置、系统盘接近写满的原因。

| 存储 | 实施前可用 | 实施后可用 | 当前用途 |
| --- | ---: | ---: | --- |
| `/dev/sda2` → `/` | 1.1 GiB | 50.5 GiB | 宿主系统、原有 `/opt` 等绑定目录 |
| `/dev/sdb1` → `/mnt/data` | 221.7 GiB | 169.0 GiB | Docker/containerd 存储与迁移备份 |

清理已核验旧副本回收 **49.1 GiB**。原有 `downloads`、`jdstar` 和其他业务目录保留，未格式化或重新分区。

| 物理目录 | 运行时访问路径 |
| --- | --- |
| `/mnt/data/docker` | `/var/lib/docker`（bind mount） |
| `/mnt/data/containerd` | `/var/lib/containerd`（bind mount） |

`/etc/fstab` 已持久配置两个 bind mount 与独立盘挂载依赖；`/etc/systemd/system/{docker,containerd}.service.d/storage.conf` 要求挂载就绪并断言挂载点存在。Docker、Docker socket 和 containerd 的临时 mask 已解除，三个单元均保持 `enabled`。原 daemon 配置、Docker 代理 drop-in 和容器 restart 策略保留。

## 验收记录

- 两个存储目录的完整 rsync 内容校验均通过，原始结果在 `attempt-3/{containerd,docker}-checksum-diff.txt`，均为空。后续回退恢复期间产生的增量由 rsync 传输校验同步，最后复核文件清单、大小、时间、权限、UID/GID、硬链接、ACL 与扩展属性；未重复读取已校验的整套镜像。
- 52 个容器的 ID/镜像与运行状态一致：29 个运行、23 个停止，运行容器没有异常健康状态。镜像与数据卷比较使用 ID、标签、摘要、驱动、挂载点和配置等稳定字段。
- Nora、Sub2API、Agent Drive、meme 四个 PostgreSQL 实际查询成功；Nora 模型/设置/MCP 配置签名不变、历史记录保留。
- Nora 内网及公网 HTTPS 返回 200，Sub2API 内部入口及 mux 健康接口返回 200。真实模型会话 `storage-migration-check-20261010` 回答 `STORAGE_MIGRATION_OK` 并成功保存。
- 1Panel、nginx、OpenClaw 和 Agent Drive 宿主服务保持运行；Agent Drive 8010 的健康接口返回 `UP`。
- SSD 负载后 SMART 仍为 `PASSED`，温度 40°C，重映射、待处理、不可纠正扇区和 CRC 错误均为 0。

这次维护超出最初 5–10 分钟的估计。13:24:01 首次停止，到 13:56:20 最终恢复，期间三次回退并恢复原存储：首次被外部重新拉起运行时；第二次提前中止以修正解除 mask 和单元验证的顺序；第三次全量校验已通过，但清单比较误把 `CreatedSince` 的自然变化算成镜像变化。第四次改为稳定字段比较，复用完整内容校验并同步最终增量，3 分 18 秒完成切换与恢复。

## 备份与后续维护

服务器记录保存在 `/mnt/data/megumin-storage-migration-20261010`（权限 0700），包括原配置、容器清单、三次回退记录、SMART、业务验收与 `final-verification.json`。

`docker-before.tar`（约 3.2 GiB）保留切换时的数据卷和 Docker 元数据，归档与源文件比较通过，SHA-256 见同目录 `.sha256` 文件。tar 略过一个停止的 Dify 容器遗留的 UNIX socket；该 socket 属于运行时资源。归档范围不含 `/opt` 等绑定挂载数据，原应用备份保留。系统盘的两个 `.pre-data-20261010` 目录已删除。

清理后若要迁回，应先停止业务并临时禁止运行时被拉起，将**当前独立盘数据**同步到目的盘，再调整挂载和服务依赖；历史归档不含切换后的新写入，不能直接覆盖运行中的目录。没有进行整机重启或物理断盘验证；已完成 systemd/fstab 配置验证、运行时启动及清理后的实际业务检查。

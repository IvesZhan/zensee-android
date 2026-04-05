# ZenSee Android 安全检查清单

> 用途：在代码公开、发布 APK、推送仓库前，快速检查是否有敏感信息误入仓库或发布产物。

## 1. 本地配置检查

- [ ] `key.properties` 存在于本地，但没有被 Git 跟踪
- [ ] `key.properties` 只保存在本机或受控的私有备份中
- [ ] 仓库中只存在 `key.properties.example`，不包含真实值
- [ ] `local.properties` 没有被 Git 跟踪

## 2. 敏感信息检查

- [ ] 源码中没有硬编码：
  - Supabase URL
  - Supabase anon key
  - 签名 keystore 密码
  - 其他 token / secret / password
- [ ] 发布前运行一次全文扫描：

```bash
rg --hidden -n "supabase\\.co|eyJ|apikey|secret|token|password=|storePassword=|keyPassword=" . \
  -g '!**/.git/**' \
  -g '!**/build/**' \
  -g '!**/.gradle/**' \
  -g '!**/.gradle-publish/**' \
  -g '!key.properties'
```

- [ ] 上面的扫描结果里，没有真实密钥、JWT、密码或项目 URL 明文残留

## 3. Git 检查

- [ ] `git status --short` 没有意外文件
- [ ] 没有把以下内容加入提交：
  - `key.properties`
  - `local.properties`
  - `.gradle/`
  - `.gradle-publish/`
  - `.idea/`
  - 签名 keystore 文件
- [ ] 提交前确认 `.gitignore` 仍然覆盖上述本地文件

## 4. 发布检查

- [ ] 运行：

```bash
./scripts/publish-release.sh
```

- [ ] `assembleRelease` 成功
- [ ] `apksigner verify` 成功
- [ ] `downloads/latest/ZenSee-android-latest.apk` 已更新
- [ ] `downloads/latest/SHA256.txt` 已更新

## 5. 公开仓库检查

- [ ] 推送前确认本次提交没有把真实配置带进仓库
- [ ] 推送后可抽查：

```bash
git grep -n "supabase\\.co\\|eyJ" HEAD
```

- [ ] 稳定下载链接仍然可用：
  - `https://raw.githubusercontent.com/IvesZhan/zensee-android/main/downloads/latest/ZenSee-android-latest.apk`

## 6. 历史与止损检查

- [ ] 如果曾误推敏感信息，先判断是否已公开暴露
- [ ] 如果只是刚推上去且无人 fork / clone，可考虑重写 Git 历史
- [ ] 如果无法确认是否已暴露，默认视为已泄露，优先轮换密钥

## 7. 日常规则

- 不要把任何真实密钥写进：
  - Kotlin 源码
  - XML 资源
  - README
  - 文档
  - 发布脚本输出
- 公开仓库里只保留：
  - `key.properties.example`
  - 构建读取逻辑
  - 不含真实值的说明文档

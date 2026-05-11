# CLAUDE.md

## teamsdocs

`teamsdocs` 是通过 `git submodule` 管理的子模块，指向 `https://github.com/hua0424/aichat-hula-docs.git`。

**对该目录执行任何提交或更新操作时，必须使用 git submodule 相关命令：**

- 拉取更新：`git submodule update --remote --merge teamsdocs`
- 进入目录后提交：先 `cd teamsdocs`，然后正常 `git add` / `git commit` / `git push`，最后回到父仓库执行 `git add teamsdocs` 提交子模块指针变更
- 初始化/克隆：`git submodule update --init --recursive`
- 查看状态：`git submodule status`

**切勿**直接在父仓库中对 `teamsdocs` 目录内的文件做 `git add` / `git commit` — 子模块目录在父仓库中只记录为一个提交指针，而不是文件内容。

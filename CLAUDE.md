# CLAUDE.md

## team

This repo is part of the AIChat(HuLa) project collaboration network via `claude-peers`.

**Identity:** Use `whoami` to confirm your current peer ID and role. Do NOT assume a fixed identity — different members may check out this repo.

**Team roster (brief):**

| Peer ID | Role | Scope |
|---------|------|-------|
| manager | manager | PRD, task coordination, teamdocs maintenance |
| frontend-dev | developer | HuLa + HuLa-Admin frontend |
| server-dev | developer | HuLa-Server backend |
| plugin-dev | developer | aichat-plugins bridge |
| backend-tester | tester | API/integration testing, CI/CD, runtime env |
| ui-tester | tester | Windows/Android client testing |
| reviewer | reviewer | PRD review, code review (reports to manager) |

For full details (responsibilities, workflow, contacts): `get_group_doc` or read `teamdocs/group_doc.md`.

**Scope rules:** Only modify `teamdocs/`. Code changes in frontend/plugins/server must go through the respective developer peer.

**Coordination:** Update `set_summary` daily. Use `send_message` for short comms; write to `teamdocs/` for docs/reports then send the path.

## teamdocs

`teamdocs` 是通过 `git submodule` 管理的子模块，指向 `https://github.com/hua0424/aichat-hula-docs.git`。

**对该目录执行任何提交或更新操作时，必须使用 git submodule 相关命令：**

- 拉取更新：`git submodule update --remote --merge teamdocs`
- 进入目录后提交：先 `cd teamdocs`，然后正常 `git add` / `git commit` / `git push`，最后回到父仓库执行 `git add teamdocs` 提交子模块指针变更
- 初始化/克隆：`git submodule update --init --recursive`
- 查看状态：`git submodule status`

**切勿**直接在父仓库中对 `teamdocs` 目录内的文件做 `git add` / `git commit` — 子模块目录在父仓库中只记录为一个提交指针，而不是文件内容。

## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- After modifying code files in this session, run `python3 -c "from graphify.watch import _rebuild_code; from pathlib import Path; _rebuild_code(Path('.'))"` to keep the graph current

## compact

When summarizing, always preserve:
- The current design plan and architecture decisions
- All files modified so far and their key changes
- Unresolved TODOs and next steps
- Any errors encountered and how they were fixed
- Current task state (what's done, what's in progress)

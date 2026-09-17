#!/usr/bin/env python3
"""同步 / 校验「全客户端通用 AI Skill」资产。

设计见 docs/ai-skill.md。模式与 design-assets/tokens 相同：
唯一人工维护的源在根仓 design-assets/ai-skill/，各端只读自己工作树内的副本。

    python scripts/generate-ai-skill.py --check              # 校验源资产自身
    python scripts/generate-ai-skill.py --android            # 同步到 Android assets
    python scripts/generate-ai-skill.py --desktop            # 同步到桌面端 public
    python scripts/generate-ai-skill.py --check --android    # 校验副本没有漂移

不联网。清单只记录 skill_version 与各文件 sha256，不记录时间戳，保证可复现。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE_DIR = REPO_ROOT / "design-assets" / "ai-skill"
MANIFEST_NAME = "manifest.json"
SKILL_VERSION = "tplanner.plan-extract@1"

# 必须同步到各端的文件（相对源目录）。manifest.json 在同步时重新生成，不从这里复制。
SYNCED_FILES = ("system-prompt.md", "tools/create-plan.json", "assertions.md")

# 消费方：命令行开关 -> 目标目录
TARGETS = {
    "android": REPO_ROOT / "app" / "src" / "main" / "assets" / "ai-skill",
    "desktop": REPO_ROOT / ".v5-worktrees" / "desktop" / "public" / "ai-skill",
}

ASSERTION_VOCABULARY = {
    "topic-count", "action-count", "every-topic-has-actions", "mentioned-topic",
    "mentioned-action", "time-source-exists", "time-source-at-least",
    "no-time-invented-as-stated", "inferred-has-basis", "inferred-times-ordered",
    "inferred-confidence-band", "time-within", "no-time-before-now",
    "first-action-has-evidence", "subtasks-verb-first", "empty-topics-when-not-a-task",
    "degraded-matches-contract",
}

TIME_SOURCES = {"stated", "inferred", "none"}


def fail(message: str) -> None:
    print(f"错误：{message}", file=sys.stderr)
    raise SystemExit(1)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    digest.update(path.read_bytes())
    return digest.hexdigest()


def synced_file_list() -> list[str]:
    files = list(SYNCED_FILES)
    files += sorted(
        str(p.relative_to(SOURCE_DIR)).replace("\\", "/")
        for p in (SOURCE_DIR / "fixtures").glob("*.json")
    )
    return sorted(files)


def build_manifest() -> dict:
    files = synced_file_list()
    # 断言词表的定义文件本身也要进哈希：改词表就会让所有副本的 manifest 变化。
    hashes = {name: sha256_of(SOURCE_DIR / name) for name in files}
    return {
        "skill_version": SKILL_VERSION,
        "entry": {
            "system_prompt": "system-prompt.md",
            "tool": "tools/create-plan.json",
            "fixtures": "fixtures",
            "assertions": "assertions.md",
        },
        "files": hashes,
    }


# ── 源资产校验 ────────────────────────────────────────────────────────────

def check_source(check_only: bool = False) -> dict:
    if not SOURCE_DIR.is_dir():
        fail(f"找不到源目录 {SOURCE_DIR}")

    prompt = SOURCE_DIR / "system-prompt.md"
    if not prompt.is_file():
        fail("缺少 system-prompt.md")
    prompt_text = prompt.read_text(encoding="utf-8")
    if "create_plan" not in prompt_text:
        fail("system-prompt.md 必须要求调用 create_plan")

    tool_path = SOURCE_DIR / "tools" / "create-plan.json"
    try:
        tool = json.loads(tool_path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        fail("缺少 tools/create-plan.json")
    except json.JSONDecodeError as error:
        fail(f"tools/create-plan.json 不是合法 JSON：{error}")

    if tool.get("name") != "create_plan":
        fail(f"工具名必须是 create_plan，实际是 {tool.get('name')!r}")
    if tool.get("strict") is True:
        # strict 模式（Beta）要求所有对象 additionalProperties=false 且属性全部必填。
        problems = strict_schema_problems(tool.get("parameters", {}))
        if problems:
            fail("strict 模式 schema 不合规：\n  - " + "\n  - ".join(problems))
    else:
        print("提示：tools/create-plan.json 未开启 strict（Beta）。运行时用 tool_choice=auto + 客户端校验兜底。")

    fixture_paths = sorted((SOURCE_DIR / "fixtures").glob("*.json"))
    if not fixture_paths:
        fail("fixtures/ 下没有任何语料")
    for path in fixture_paths:
        check_fixture(path)

    manifest = build_manifest()
    if check_only:
        check_source_manifest(manifest)
    else:
        write_manifest(SOURCE_DIR / MANIFEST_NAME, manifest)
    print(f"源资产校验通过：{len(fixture_paths)} 条语料，"
          f"{len(manifest['files'])} 个文件，skill_version={SKILL_VERSION}")
    return manifest


def check_source_manifest(manifest: dict) -> None:
    """--check 不写任何文件：清单过期就报错，避免"校验"顺手改坏工作区。"""
    recorded_path = SOURCE_DIR / MANIFEST_NAME
    if not recorded_path.is_file():
        fail(f"缺少 {MANIFEST_NAME}；先运行不带 --check 的同一命令生成")
    try:
        recorded = json.loads(recorded_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        fail(f"{MANIFEST_NAME} 不是合法 JSON：{error}")
    if recorded.get("skill_version") != manifest["skill_version"]:
        fail(f"{MANIFEST_NAME} 的 skill_version 过期："
             f"{recorded.get('skill_version')!r} != {manifest['skill_version']!r}")
    for relative, expected in manifest["files"].items():
        if recorded.get("files", {}).get(relative) != expected:
            fail(f"{MANIFEST_NAME} 与 {relative} 不一致；重新生成清单")
    extra = sorted(set(recorded.get("files", {})) - set(manifest["files"]))
    if extra:
        fail(f"{MANIFEST_NAME} 记录了已不存在的文件：{extra}")


def strict_schema_problems(node: object, path: str = "parameters") -> list[str]:
    """检查 strict 模式的两条硬性要求，以及文档明确不支持的关键字。"""
    problems: list[str] = []
    if isinstance(node, dict):
        if node.get("type") == "object":
            properties = node.get("properties", {})
            if node.get("additionalProperties") is not False:
                problems.append(f"{path}: object 必须 additionalProperties=false")
            required = set(node.get("required", []))
            missing = sorted(set(properties) - required)
            if missing:
                problems.append(f"{path}: strict 要求全部属性必填，缺少 {missing}")
        for unsupported in ("minItems", "maxItems", "minLength", "maxLength"):
            if unsupported in node:
                problems.append(f"{path}: strict 不支持 {unsupported}")
        for key, value in node.items():
            problems += strict_schema_problems(value, f"{path}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            problems += strict_schema_problems(value, f"{path}[{index}]")
    return problems


def check_fixture(path: Path) -> None:
    try:
        fixture = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        fail(f"{path.name} 不是合法 JSON：{error}")

    request = fixture.get("request")
    if not isinstance(request, dict):
        fail(f"{path.name} 缺少 request 对象")
    if request.get("skill") != SKILL_VERSION:
        fail(f"{path.name} 的 skill 版本是 {request.get('skill')!r}，应为 {SKILL_VERSION!r}")

    now = request.get("now") or {}
    for key in ("instant", "time_zone", "weekday", "relative"):
        if key not in now:
            fail(f"{path.name} 的 request.now 缺少 {key}")
    if not isinstance(request.get("thread"), list) or not request["thread"]:
        fail(f"{path.name} 的 request.thread 必须是非空数组")

    expectations = fixture.get("expect")
    if not isinstance(expectations, list) or not expectations:
        fail(f"{path.name} 缺少 expect 断言列表")
    for index, expectation in enumerate(expectations):
        name = expectation.get("assert")
        if name not in ASSERTION_VOCABULARY:
            fail(f"{path.name} 第 {index + 1} 条断言用了未定义的关键字 {name!r}（见 assertions.md）")
        source = expectation.get("source")
        if source is not None and source not in TIME_SOURCES:
            fail(f"{path.name} 第 {index + 1} 条断言的 source 非法：{source!r}")


# ── 各端副本 ──────────────────────────────────────────────────────────────

def write_manifest(path: Path, manifest: dict) -> None:
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def check_target(name: str, target: Path, manifest: dict) -> bool:
    if not target.is_dir():
        print(f"[{name}] 尚未同步：{target} 不存在")
        return False
    ok = True
    for relative, expected in manifest["files"].items():
        copy = target / relative
        if not copy.is_file():
            print(f"[{name}] 缺少 {relative}")
            ok = False
            continue
        actual = sha256_of(copy)
        if actual != expected:
            print(f"[{name}] {relative} 与源不一致（{actual[:12]}… != {expected[:12]}…）")
            ok = False
    copy_manifest = target / MANIFEST_NAME
    if not copy_manifest.is_file():
        print(f"[{name}] 缺少 {MANIFEST_NAME}")
        ok = False
    else:
        try:
            recorded = json.loads(copy_manifest.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            print(f"[{name}] {MANIFEST_NAME} 不是合法 JSON：{error}")
            ok = False
        else:
            if recorded.get("skill_version") != manifest["skill_version"]:
                print(f"[{name}] skill_version 不一致：{recorded.get('skill_version')!r}")
                ok = False
    print(f"[{name}] {'一致' if ok else '不一致'}：{target}")
    return ok


def sync_target(name: str, target: Path, manifest: dict) -> None:
    (target / "tools").mkdir(parents=True, exist_ok=True)
    (target / "fixtures").mkdir(parents=True, exist_ok=True)

    stale = set()
    for existing in target.rglob("*"):
        if existing.is_file():
            stale.add(str(existing.relative_to(target)).replace("\\", "/"))
    stale -= set(manifest["files"]) | {MANIFEST_NAME}
    for relative in sorted(stale):
        (target / relative).unlink()
        print(f"[{name}] 删除已不在源中的 {relative}")

    for relative in manifest["files"]:
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(SOURCE_DIR / relative, destination)
    write_manifest(target / MANIFEST_NAME, manifest)
    print(f"[{name}] 已同步 {len(manifest['files'])} 个文件 → {target}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in TARGETS:
        parser.add_argument(f"--{name}", action="store_true", help=f"同步到 {TARGETS[name]}")
    parser.add_argument("--check", action="store_true", help="只校验，不写入目标目录")
    options = parser.parse_args()

    manifest = check_source(check_only=options.check)

    selected = [name for name in TARGETS if getattr(options, name)]
    if not selected:
        if options.check:
            print("未指定目标端；源资产已校验。")
        else:
            print("未指定目标端：用 --android / --desktop 同步（--check 可校验一致性）。")
        return 0

    ok = True
    for name in selected:
        target = TARGETS[name]
        if options.check:
            ok = check_target(name, target, manifest) and ok
        else:
            sync_target(name, target, manifest)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

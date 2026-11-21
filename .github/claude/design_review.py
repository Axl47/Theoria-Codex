"""Claude design review: calls the Claude API directly, with read-only tools over the pull request.

The workflow runs this file from the default branch, never from the pull request, and points it at a
checkout of the pull request's head. Claude can read, list and search files inside that checkout and
nothing else: there is no shell, no file writing and no network tool. Its final message is the review.

Usage (from the workflow):
  python design_review.py --root pr --prompt base/.github/claude/design-review.md \
      --task "Review the design of pull request #12 in owner/repo." --out pr/.claude-review/review.md

On an API error it prints Anthropic's error type and message and exits 1. It always prints the token
usage and an estimated cost, to the log and to the job summary.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

import anthropic

MODEL = "claude-opus-5-5"
EFFORT = "high"
MAX_TOKENS = 16000
MAX_REQUESTS = 40  # Tool-use turns before Claude is told to write the review with what it has.
TOOL_OUTPUT_LIMIT = 60_000  # Characters returned from one tool call.
READ_LINE_LIMIT = 2000
SEARCH_MATCH_LIMIT = 200
LIST_RESULT_LIMIT = 400

# Estimated USD per million tokens for MODEL (5-minute cache writes cost 1.25x input).
PRICE_INPUT = 4.00
PRICE_CACHE_WRITE = 5.00
PRICE_CACHE_READ = 0.20
PRICE_OUTPUT = 20.00

TOOLS = [
    {
        "name": "read_file",
        "description": (
            "Read a text file from the pull request's checkout. Paths are relative to the repository "
            "root, for example 'app/build.gradle.kts' or '.claude-review/pr.diff'. Returns numbered "
            f"lines, at most {READ_LINE_LIMIT} per call; use start_line to read further."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path relative to the repository root."},
                "start_line": {"type": "integer", "description": "First line to return, from 1. Defaults to 1."},
            },
            "required": ["path"],
            "additionalProperties": False,
        },
    },
    {
        "name": "list_files",
        "description": (
            "List files in the checkout that match a glob pattern relative to the repository root, "
            "for example '**/*.kt' or 'core/src/main/**/Feed*.kt'."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"pattern": {"type": "string", "description": "Glob pattern, '**' matches any depth."}},
            "required": ["pattern"],
            "additionalProperties": False,
        },
    },
    {
        "name": "search",
        "description": (
            "Search file contents with an extended regular expression (grep -E). Returns matching lines "
            f"as path:line:text, at most {SEARCH_MATCH_LIMIT}. Optionally limit to a directory and a "
            "file-name glob such as '*.kt'."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Extended regular expression."},
                "path": {"type": "string", "description": "Directory or file relative to the repository root. Defaults to the root."},
                "file_glob": {"type": "string", "description": "Only search files whose name matches this glob, e.g. '*.kt'."},
            },
            "required": ["pattern"],
            "additionalProperties": False,
        },
    },
]


class ToolError(Exception):
    pass


class Workspace:
    """Read-only access to one directory tree. Every path is resolved and must stay inside it."""

    def __init__(self, root: Path):
        self.root = root.resolve()

    def resolve(self, relative: str) -> Path:
        candidate = (self.root / relative.lstrip("/")).resolve()
        if candidate != self.root and self.root not in candidate.parents:
            raise ToolError(f"{relative} is outside the repository.")
        if ".git" in candidate.relative_to(self.root).parts:
            raise ToolError("The .git directory is not available.")
        return candidate

    def display(self, path: Path) -> str:
        return str(path.relative_to(self.root)) or "."

    def read_file(self, path: str, start_line: int = 1) -> str:
        target = self.resolve(path)
        if not target.is_file():
            raise ToolError(f"{path} is not a file.")
        data = target.read_bytes()
        if b"\0" in data[:8192]:
            raise ToolError(f"{path} looks like a binary file.")
        lines = data.decode("utf-8", errors="replace").splitlines()
        start = max(start_line, 1)
        chunk = lines[start - 1 : start - 1 + READ_LINE_LIMIT]
        body = "\n".join(f"{number:6}\t{line}" for number, line in enumerate(chunk, start))
        end = start + len(chunk) - 1
        if end < len(lines):
            body += f"\n[Lines {start}-{end} of {len(lines)}. Call again with start_line={end + 1} for more.]"
        return body or f"[{path} is empty, or has fewer than {start} lines.]"

    def list_files(self, pattern: str) -> str:
        if ".." in Path(pattern).parts:
            raise ToolError("Patterns cannot contain '..'.")
        matches = []
        for found in self.root.glob(pattern.lstrip("/")):
            if ".git" in found.relative_to(self.root).parts or not found.is_file():
                continue
            matches.append(self.display(found))
            if len(matches) > LIST_RESULT_LIMIT:
                break
        matches.sort()
        if not matches:
            return "No files match."
        note = f"\n[Stopped at {LIST_RESULT_LIMIT} files; narrow the pattern.]" if len(matches) > LIST_RESULT_LIMIT else ""
        return "\n".join(matches[:LIST_RESULT_LIMIT]) + note

    def search(self, pattern: str, path: str = ".", file_glob: str = "") -> str:
        target = self.resolve(path or ".")
        command = ["grep", "-rnIE", "--exclude-dir=.git", "--max-count=50"]
        if file_glob:
            command.append(f"--include={file_glob}")
        command += ["-e", pattern, "--", str(target)]
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=30, cwd=self.root)
        except subprocess.TimeoutExpired:
            raise ToolError("The search took longer than 30 seconds; narrow the path or pattern.")
        if result.returncode == 2:
            raise ToolError(result.stderr.strip()[:500] or "grep failed.")
        prefix = str(self.root) + os.sep
        lines = [line.removeprefix(prefix) for line in result.stdout.splitlines()]
        if not lines:
            return "No matches."
        note = f"\n[Showing {SEARCH_MATCH_LIMIT} of {len(lines)} matches; narrow the search.]" if len(lines) > SEARCH_MATCH_LIMIT else ""
        return "\n".join(lines[:SEARCH_MATCH_LIMIT]) + note

    def run(self, name: str, arguments: dict) -> str:
        if name == "read_file":
            output = self.read_file(arguments["path"], int(arguments.get("start_line") or 1))
        elif name == "list_files":
            output = self.list_files(arguments["pattern"])
        elif name == "search":
            output = self.search(arguments["pattern"], arguments.get("path") or ".", arguments.get("file_glob") or "")
        else:
            raise ToolError(f"Unknown tool {name}.")
        if len(output) > TOOL_OUTPUT_LIMIT:
            output = output[:TOOL_OUTPUT_LIMIT] + "\n[Output cut short; ask for less.]"
        return output


class Usage:
    def __init__(self):
        self.requests = 0
        self.input = self.cache_write = self.cache_read = self.output = 0

    def add(self, usage) -> None:
        self.requests += 1
        self.input += usage.input_tokens or 0
        self.cache_write += getattr(usage, "cache_creation_input_tokens", 0) or 0
        self.cache_read += getattr(usage, "cache_read_input_tokens", 0) or 0
        self.output += usage.output_tokens or 0

    def cost(self) -> float:
        return (
            self.input * PRICE_INPUT
            + self.cache_write * PRICE_CACHE_WRITE
            + self.cache_read * PRICE_CACHE_READ
            + self.output * PRICE_OUTPUT
        ) / 1_000_000

    def report(self) -> None:
        line = (
            f"claude-design-review usage: model={MODEL} requests={self.requests} input_tokens={self.input} "
            f"cache_write_tokens={self.cache_write} cache_read_tokens={self.cache_read} "
            f"output_tokens={self.output} estimated_cost_usd={self.cost():.4f}"
        )
        print(line)
        summary(
            "### Claude design review usage\n\n"
            "| Requests | Input | Cache write | Cache read | Output | Estimated cost |\n"
            "|---|---|---|---|---|---|\n"
            f"| {self.requests} | {self.input:,} | {self.cache_write:,} | {self.cache_read:,} | "
            f"{self.output:,} | ${self.cost():.2f} |\n\n"
            f"<!-- {line} -->\n"
        )


def summary(text: str) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(text + "\n")


def fail(message: str) -> None:
    print(f"::error title=Claude design review failed::{message.replace(chr(10), ' ')}")
    summary(f"### Claude design review failed\n\n```\n{message}\n```")


def api_error_text(error: anthropic.APIStatusError) -> str:
    detail = error.body.get("error", {}) if isinstance(error.body, dict) else {}
    kind = detail.get("type", "unknown_error")
    message = detail.get("message") or str(error.message)
    request_id = error.response.headers.get("request-id", "")
    suffix = f" (request id {request_id})" if request_id else ""
    return f"Anthropic API error {error.status_code} {kind}: {message}{suffix}"


def review(client: anthropic.Anthropic, workspace: Workspace, system: str, task: str, usage: Usage) -> str:
    messages: list = [{"role": "user", "content": task}]
    for request in range(1, MAX_REQUESTS + 2):
        last = request > MAX_REQUESTS
        if last:
            messages.append({
                "role": "user",
                "content": "You have used every tool turn. Write the review now from what you have read.",
            })
        response = client.beta.messages.create(
            model=MODEL,
            max_tokens=MAX_TOKENS,
            system=system,
            tools=TOOLS,
            tool_choice={"type": "none"} if last else {"type": "auto"},
            messages=messages,
            output_config={"effort": EFFORT},
            cache_control={"type": "ephemeral"},
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
        )
        usage.add(response.usage)
        messages.append({"role": "assistant", "content": response.content})

        if response.stop_reason == "refusal":
            details = response.stop_details
            category = getattr(details, "category", None) if details else None
            raise RuntimeError(f"Claude declined to review this pull request (refusal category: {category}).")
        if response.stop_reason == "pause_turn":
            continue
        tool_calls = [block for block in response.content if block.type == "tool_use"]
        if response.stop_reason != "tool_use" or not tool_calls:
            text = "\n".join(block.text for block in response.content if block.type == "text").strip()
            if response.stop_reason == "max_tokens":
                print("::warning::The review reached the output limit and may be cut short.")
            return text

        results = []
        for call in tool_calls:
            try:
                content, is_error = workspace.run(call.name, call.input), False
            except (ToolError, OSError, KeyError, ValueError, TypeError, AttributeError) as error:
                content, is_error = f"Error: {error}", True
            print(f"tool {call.name} {json.dumps(call.input)[:200]}{' (error)' if is_error else ''}")
            results.append({"type": "tool_result", "tool_use_id": call.id, "content": content, "is_error": is_error})
        messages.append({"role": "user", "content": results})
    raise RuntimeError("Claude did not finish the review.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, help="Checkout of the pull request's head.")
    parser.add_argument("--prompt", required=True, help="The reviewer's instructions (system prompt).")
    parser.add_argument("--task", required=True, help="The first user message.")
    parser.add_argument("--out", required=True, help="Where to write the review.")
    args = parser.parse_args()

    workspace = Workspace(Path(args.root))
    system = Path(args.prompt).read_text(encoding="utf-8")
    client = anthropic.Anthropic(max_retries=5, timeout=600)
    usage = Usage()
    try:
        text = review(client, workspace, system, args.task, usage)
    except anthropic.APIStatusError as error:
        fail(api_error_text(error))
        return 1
    except anthropic.APIConnectionError as error:
        fail(f"Could not reach the Anthropic API: {error}")
        return 1
    except RuntimeError as error:
        fail(str(error))
        return 1
    finally:
        usage.report()

    if not text:
        fail("Claude finished without writing a review.")
        return 1
    Path(args.out).write_text(text + "\n", encoding="utf-8")
    print(f"Wrote the review ({len(text)} characters) to {args.out}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

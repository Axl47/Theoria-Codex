# Codex Execution Plans (HTML ExecPlans)

This document defines the standard for VESTIGE execution plans. An ExecPlan is a self-contained implementation guide that a coding agent or human developer can open in a browser, read from top to bottom, and use to deliver a working feature or system change.

New ExecPlans must be standalone `.html` files written to `.docs/exec/`.

## Why HTML Plans

HTML plans are preferred because they can present more information without turning into a wall of text. They support a sticky table of contents, dense summary panels, progress tables, collapsible evidence, diagrams, screenshots, annotated images, code blocks, and print-friendly styling. The goal is not decorative documentation. The goal is a plan that is easier for an agent to execute and easier for the developer to audit.

The plan must still be plain, portable, and durable. Use one self-contained HTML document with embedded CSS. Avoid external CDNs, remote fonts, framework bundles, or build steps. JavaScript is optional and should be avoided unless it directly improves local readability. A plan must remain useful when opened from disk in a browser.

## How To Use ExecPlans

When authoring an ExecPlan, read this entire file first. Start from the HTML skeleton in this document, then fill it in as you inspect the repository. Do not write aspirational placeholder sections and leave them stale. Every section should help a future reader restart the work with only the current working tree and the single plan file.

When implementing an ExecPlan, keep the HTML file current as the work changes. Update progress, decisions, surprises, validation evidence, and retrospective notes at every meaningful stopping point. Do not ask the user for "next steps" when the next phase is already defined by the plan. Resolve ordinary implementation details autonomously and record the decision.

When discussing an ExecPlan with the developer, record the decision and rationale in the plan. The plan is the durable memory. It should explain what changed, why it changed, and what evidence supports the current direction.

When researching uncertain work, include prototype phases. A prototype phase may create a small proof of concept, compare two approaches, measure performance, or validate an integration. Keep prototypes additive and easy to remove. State the criteria for promoting or discarding the prototype.

## Non-Negotiable Requirements

- Every ExecPlan must be fully self-contained. If a novice needs a fact, command, file path, domain term, or implementation constraint, include it in the plan.
- Every ExecPlan must be a living document. Progress, decisions, discoveries, validation, and outcomes must be updated as work proceeds.
- Every ExecPlan must produce demonstrably working behavior. The plan cannot stop at "change the code"; it must explain how to see the result working.
- Every ExecPlan must define project-specific or technical terms in plain language the first time they appear.
- Every ExecPlan must name exact repository-relative file paths, functions, modules, commands, and expected observations.
- Every ExecPlan must include safe retry or recovery guidance for commands or edits that can fail halfway.
- Every ExecPlan must be readable by both an agent and the developer. Dense is good; cryptic is not.

## File And Asset Conventions

Create new plans at:

```txt
.docs/exec/<kebab-case-plan-name>.html
```

If the plan needs images, screenshots, diagrams, or other supporting media, place them beside the plan in a matching asset directory:

```txt
.docs/exec/assets/<kebab-case-plan-name>/<asset-name>.<ext>
```

Reference assets with relative paths such as:

```html
<img src="./assets/manual-waterfall-sweeps/sweep-hitbox.png" alt="Annotated sweep hitbox areas">
```

Use descriptive `alt` text. If an image contains critical information, summarize that information in nearby text so the plan remains usable without the image.

## Information Architecture

An HTML ExecPlan should optimize for two readers at once:

- The agent needs exact implementation instructions, commands, acceptance checks, and recovery steps.
- The developer needs a fast way to understand scope, risks, decisions, and current status.

Use this page structure:

- Header: short title, status, owner, dates, and a one-paragraph outcome.
- Sticky navigation: links to the major sections.
- Dashboard: compact cards for current state, risk, validation, affected files, and next action.
- Purpose: what the player or developer can do after the change that they could not do before.
- Context: repository orientation, terms, existing behavior, constraints, and relevant files.
- Phases: independently verifiable phases with observable outcomes.
- Work Plan: exact edits and implementation sequence.
- Validation: commands, manual checks, expected output, and acceptance criteria.
- Living Log: progress, surprises, decisions, and retrospective.
- Recovery: idempotence, rollback, cleanup, and how to resume.
- Appendix: evidence, command transcripts, snippets, diagrams, or references.

Prefer short paragraphs, compact tables, and focused callouts. Use `<details>` for large evidence blocks or secondary explanations. Do not hide the primary plan inside collapsed sections.

## Required Sections

Each plan must include these sections, with these purposes:

### Purpose / Big Picture

Explain why the work matters from the user or developer perspective. State the concrete behavior that will exist when the plan is complete and how someone can see it working.

### Current Status

Show whether the plan is proposed, in progress, blocked, validating, or complete. Include the next action. This should be visible near the top of the page.

### Context And Orientation

Describe the relevant code as if the reader knows nothing about this repository. Name files by full repository-relative path. Define terms. Explain how the parts fit together.

### Phases

Break the work into independently verifiable phases. Each phase needs a goal, specific edits, commands to run, expected observations, and acceptance criteria. Phases tell the story of the implementation; they are not just a checklist.

### Work Plan

Describe the exact sequence of edits. Name the files, functions, modules, components, types, and tests that should change. Make choices explicit. If you choose not to touch a related area, say why.

### Validation And Acceptance

List exact commands and manual checks. Include the working directory, expected results, and how to interpret failures. Acceptance must be behavior-based, such as "the Save Bank preset opens the late-game station without console errors" rather than "component refactored."

### Progress

Maintain a dated checklist of granular work. Every stopping point must be reflected here. Split partially completed work into completed and remaining pieces instead of leaving ambiguous items.

### Surprises And Discoveries

Record unexpected behavior, bugs, performance findings, library constraints, or design insights. Include concise evidence, such as test output or a relevant code reference.

### Decision Log

Record decisions in a durable format: decision, rationale, date, and author. Decisions should explain why the current approach is correct enough for the project.

### Outcomes And Retrospective

At major phases and completion, summarize what changed, what remains, what validation proved, and what future agents should remember.

### Idempotence And Recovery

Explain how to rerun commands safely, recover from partial edits, clean generated files, and resume if interrupted. If no destructive operations are expected, state that explicitly.

## Visual And Readability Guidelines

Use HTML features to improve comprehension:

- Use a sticky `<nav>` so long plans are easy to scan.
- Use compact summary cards for status, next action, risk, validation, and affected files.
- Use tables for structured status, command lists, affected files, and acceptance criteria.
- Use `<details><summary>...</summary>...</details>` for long transcripts, diffs, alternatives, or deep background.
- Use `<figure>` and `<figcaption>` for screenshots, diagrams, or annotated references.
- Use callout classes such as `note`, `risk`, `decision`, and `validation` to make important information scannable.
- Use `<pre><code>` for commands, code snippets, and terminal output.
- Keep CSS embedded in a single `<style>` tag in the document head.

Do not use HTML as an excuse for noise. Avoid animations, decorative gradients, remote scripts, or layout tricks that make the plan harder to maintain. A useful plan should print reasonably, work offline, and remain readable in a plain browser.

## Evidence And Examples

Capture concise evidence. Good evidence includes:

- A short test transcript showing the command and pass/fail count.
- A browser observation with viewport and URL.
- A small diff excerpt when it clarifies the key change.
- A screenshot or annotated image that explains layout, hit areas, or visual state.
- A performance measurement before and after a change.

Do not paste huge logs or entire source files into the plan. Use summaries plus exact commands so the evidence can be reproduced.

## Safe Implementation Standards

Plans should prefer additive, testable steps. When a migration or destructive operation is necessary, describe the backup, retry, and rollback path. Commands should be safe to rerun when possible. If rerunning a command changes state, say exactly how and why.

Respect VESTIGE repository constraints in every plan.

If a future plan relies on a newer repository constraint, add it to `AGENTS.md` when it would help other developers discover the rule.

## HTML ExecPlan Skeleton

Copy this skeleton into a new `.docs/exec/<plan-name>.html` file and then replace every placeholder with concrete project knowledge. Keep the plan valid HTML.

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>ExecPlan: Short Action-Oriented Title</title>
  <style>
    :root {
      color-scheme: light dark;
      --bg: #f7f7f4;
      --surface: #ffffff;
      --text: #202124;
      --muted: #5f6368;
      --border: #d7d7d0;
      --accent: #0f766e;
      --accent-soft: #dff3ef;
      --risk: #9a3412;
      --risk-soft: #ffedd5;
      --code: #111827;
    }

    @media (prefers-color-scheme: dark) {
      :root {
        --bg: #151716;
        --surface: #202321;
        --text: #f0f2ef;
        --muted: #b8beb7;
        --border: #3a403c;
        --accent: #5eead4;
        --accent-soft: #123b37;
        --risk: #fdba74;
        --risk-soft: #43230f;
        --code: #0b0f0d;
      }
    }

    * { box-sizing: border-box; }
    body {
      margin: 0;
      font: 15px/1.55 ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: var(--bg);
      color: var(--text);
    }
    header, main {
      max-width: 1180px;
      margin: 0 auto;
      padding: 24px;
    }
    header {
      padding-top: 36px;
    }
    h1, h2, h3 {
      line-height: 1.15;
      margin: 0 0 12px;
    }
    h1 { font-size: clamp(2rem, 4vw, 3.25rem); }
    h2 { margin-top: 36px; font-size: 1.55rem; }
    h3 { margin-top: 24px; font-size: 1.1rem; }
    p { margin: 0 0 14px; }
    a { color: var(--accent); }
    nav {
      position: sticky;
      top: 0;
      z-index: 10;
      border-block: 1px solid var(--border);
      background: color-mix(in srgb, var(--surface) 92%, transparent);
      backdrop-filter: blur(10px);
    }
    nav ul {
      display: flex;
      gap: 6px;
      max-width: 1180px;
      margin: 0 auto;
      padding: 8px 24px;
      list-style: none;
      overflow-x: auto;
    }
    nav a {
      display: block;
      padding: 6px 10px;
      border-radius: 6px;
      text-decoration: none;
      white-space: nowrap;
    }
    nav a:hover { background: var(--accent-soft); }
    section {
      padding: 22px;
      margin: 18px 0;
      border: 1px solid var(--border);
      border-radius: 8px;
      background: var(--surface);
    }
    table {
      width: 100%;
      border-collapse: collapse;
      margin: 12px 0;
      font-size: 0.95rem;
    }
    th, td {
      padding: 9px 10px;
      border-bottom: 1px solid var(--border);
      text-align: left;
      vertical-align: top;
    }
    th { color: var(--muted); font-weight: 650; }
    code {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 0.92em;
    }
    pre {
      overflow-x: auto;
      padding: 14px;
      border-radius: 8px;
      background: var(--code);
      color: #f8fafc;
    }
    details {
      margin: 12px 0;
      padding: 12px 14px;
      border: 1px solid var(--border);
      border-radius: 8px;
      background: color-mix(in srgb, var(--surface) 88%, var(--bg));
    }
    summary {
      cursor: pointer;
      font-weight: 700;
    }
    figure { margin: 16px 0; }
    img {
      max-width: 100%;
      border: 1px solid var(--border);
      border-radius: 8px;
    }
    figcaption {
      margin-top: 6px;
      color: var(--muted);
      font-size: 0.92rem;
    }
    .meta {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 16px 0;
      color: var(--muted);
    }
    .pill {
      padding: 4px 8px;
      border: 1px solid var(--border);
      border-radius: 999px;
      background: var(--surface);
    }
    .dashboard {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
      gap: 12px;
      margin: 18px 0 0;
    }
    .card {
      padding: 14px;
      border: 1px solid var(--border);
      border-radius: 8px;
      background: var(--surface);
    }
    .card strong {
      display: block;
      margin-bottom: 4px;
      color: var(--muted);
      font-size: 0.82rem;
      letter-spacing: 0.02em;
      text-transform: uppercase;
    }
    .callout {
      margin: 14px 0;
      padding: 12px 14px;
      border-left: 4px solid var(--accent);
      background: var(--accent-soft);
      border-radius: 6px;
    }
    .risk {
      border-left-color: var(--risk);
      background: var(--risk-soft);
    }
    .status-table td:first-child {
      width: 180px;
      color: var(--muted);
      font-weight: 650;
    }

    @media print {
      nav { position: static; }
      section, .card { break-inside: avoid; }
      body { background: #fff; color: #000; }
    }
  </style>
</head>
<body>
  <header>
    <h1>ExecPlan: Short Action-Oriented Title</h1>
    <p>One paragraph explaining the completed outcome in user-visible terms.</p>
    <div class="meta" aria-label="Plan metadata">
      <span class="pill">Status: Proposed</span>
      <span class="pill">Owner: Codex</span>
      <span class="pill">Created: 2026-06-15</span>
      <span class="pill">Updated: 2026-06-15</span>
      <span class="pill">Plan file: .docs/exec/example.html</span>
    </div>
    <div class="dashboard">
      <div class="card"><strong>Next Action</strong>Read the relevant files and replace placeholders with concrete implementation steps.</div>
      <div class="card"><strong>Main Risk</strong>State the highest uncertainty and how the plan will reduce it.</div>
      <div class="card"><strong>Validation</strong>Name the primary automated and manual checks.</div>
      <div class="card"><strong>Affected Areas</strong>List the main files or modules.</div>
    </div>
  </header>

  <nav aria-label="Plan sections">
    <ul>
      <li><a href="#purpose">Purpose</a></li>
      <li><a href="#context">Context</a></li>
      <li><a href="#phases">Phases</a></li>
      <li><a href="#work-plan">Work Plan</a></li>
      <li><a href="#validation">Validation</a></li>
      <li><a href="#living-log">Living Log</a></li>
      <li><a href="#recovery">Recovery</a></li>
      <li><a href="#appendix">Appendix</a></li>
    </ul>
  </nav>

  <main>
    <section id="purpose">
      <h2>Purpose / Big Picture</h2>
      <p>Explain what someone gains after this change, why it matters, and how they can observe it working.</p>
    </section>

    <section id="context">
      <h2>Context And Orientation</h2>
      <p>Describe the current implementation and define domain terms. Name files with repository-relative paths.</p>
      <table>
        <thead>
          <tr><th>File or module</th><th>Role in this plan</th></tr>
        </thead>
        <tbody>
          <tr><td><code>src/example.ts</code></td><td>Replace with the actual file and why it matters.</td></tr>
        </tbody>
      </table>
    </section>

    <section id="phases">
      <h2>Phases</h2>
      <article>
        <h3>Phase 1: Concrete, Verifiable Step</h3>
        <p>Describe the scope, edits, commands, expected result, and acceptance criteria for this phase.</p>
        <table class="status-table">
          <tbody>
            <tr><td>Goal</td><td>State the observable outcome.</td></tr>
            <tr><td>Commands</td><td><code>npm test</code> or the exact project command.</td></tr>
            <tr><td>Acceptance</td><td>Describe what a human should see.</td></tr>
          </tbody>
        </table>
      </article>
    </section>

    <section id="work-plan">
      <h2>Work Plan</h2>
      <p>Describe the sequence of code and documentation edits. Be specific enough that a novice can follow it.</p>
      <ol>
        <li>Edit <code>path/to/file</code> in <code>namedFunction</code> to do the exact behavior.</li>
        <li>Add or update tests in <code>path/to/test</code> to cover the behavior.</li>
      </ol>
    </section>

    <section id="validation">
      <h2>Validation And Acceptance</h2>
      <table>
        <thead>
          <tr><th>Check</th><th>Command or action</th><th>Expected result</th></tr>
        </thead>
        <tbody>
          <tr><td>Automated tests</td><td><code>npm test</code></td><td>All tests pass; mention the expected count when known.</td></tr>
          <tr><td>Manual behavior</td><td>Open the app and perform the exact steps.</td><td>Describe the expected visible behavior.</td></tr>
        </tbody>
      </table>
    </section>

    <section id="living-log">
      <h2>Living Log</h2>

      <h3>Progress</h3>
      <ul>
        <li>[ ] 2026-06-15 - Replace this placeholder with the first real step.</li>
      </ul>

      <h3>Surprises And Discoveries</h3>
      <ul>
        <li>Observation: None yet. Evidence: Not applicable.</li>
      </ul>

      <h3>Decision Log</h3>
      <ul>
        <li>Decision: Use this plan format. Rationale: It keeps implementation context and evidence in one browsable file. Date/Author: 2026-06-15 / Codex.</li>
      </ul>

      <h3>Outcomes And Retrospective</h3>
      <p>Update this after each major phase and at completion.</p>
    </section>

    <section id="recovery">
      <h2>Idempotence And Recovery</h2>
      <p>Explain which commands are safe to rerun, how to recover from partial work, and what cleanup may be needed.</p>
    </section>

    <section id="appendix">
      <h2>Appendix: Evidence And Notes</h2>
      <details>
        <summary>Command transcript placeholder</summary>
        <pre><code>Run commands from the repository root and paste concise evidence here when useful.</code></pre>
      </details>
    </section>
  </main>
</body>
</html>
```

## Authoring Checklist

Before treating an ExecPlan as ready for implementation, verify:

- The plan is saved as `.docs/exec/<kebab-case-name>.html`.
- The document opens directly in a browser without a build step.
- The title, status, dates, next action, risk, validation, and affected areas are current.
- The purpose describes observable value, not only code structure.
- All repository terms and domain-specific phrases are defined.
- Every touched file is named with a repository-relative path.
- Phases are independently verifiable.
- Commands include working directory assumptions and expected results.
- Manual acceptance checks describe exactly what the developer should see.
- Progress, surprises, decisions, outcomes, and recovery sections are present.
- Images or screenshots use relative paths and useful `alt` text.
- No external network resources are required to read the plan.

If the guidance above is followed, a stateless agent or a human developer can open one `.html` file, understand the work, implement it safely, validate the result, and leave the plan better than they found it.

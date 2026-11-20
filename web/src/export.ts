import type { DiffResponse, Finding, LintResponse, PlanResponse, ReportResponse, Score } from './api/client.ts';
import { sentenceCase } from './lock.ts';
import { owner } from './positions.ts';
import { splitReason } from './reasons.ts';

/** Hands the browser a file to save. */
export function download(filename: string, content: string, type: string): void {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

export function downloadJson(filename: string, value: unknown): void {
  download(filename, `${JSON.stringify(value, null, 2)}\n`, 'application/json');
}

export function downloadMarkdown(filename: string, markdown: string): void {
  download(filename, markdown, 'text/markdown');
}

function fence(sql: string): string {
  return ['```sql', sql.trimEnd(), '```'].join('\n');
}

function scoreLines(score: Score): string[] {
  const lines = [`**Risk: ${score.value}/100 (${sentenceCase(score.level)})**`, ''];
  if (score.reasons.length > 0) {
    lines.push('Main reasons:', '');
    for (const reason of score.reasons) {
      const { points, rule, title } = splitReason(reason);
      lines.push(rule === null ? `- ${title}` : `- ${title} (${rule}, ${points} points)`);
    }
    lines.push('');
  }
  return lines;
}

function findingLines(finding: Finding, heading: string): string[] {
  const lines = [`${heading} ${sentenceCase(finding.severity)}: ${finding.title} (${finding.ruleId})`, '', finding.message, ''];
  if (finding.change !== null) lines.push(`- Change: \`${finding.change}\``);
  if (finding.operation?.lock) {
    const effect = finding.operation.lockEffect === null ? '' : `, ${finding.operation.lockEffect}`;
    lines.push(`- Lock: ${finding.operation.lock}${effect}`);
  }
  if (finding.change !== null || finding.operation?.lock) lines.push('');
  lines.push(`Why: ${finding.why}`, '', `Instead: ${finding.fix}`, '');
  return lines;
}

function ordered(findings: Finding[]): Finding[] {
  return findings.toSorted((a, b) => b.score - a.score);
}

export interface CompareExport {
  report: ReportResponse;
  diff?: DiffResponse;
  plan?: PlanResponse;
}

/** The risk report of a schema change, with its changes and plan when there are any. */
export function compareMarkdown({ report, diff, plan }: CompareExport): string {
  const lines = ['# SchemaGuard risk report', '', ...scoreLines(report.score), '## Risks', ''];
  if (report.findings.length === 0) lines.push('No risks found.', '');
  for (const finding of ordered(report.findings)) lines.push(...findingLines(finding, '###'));
  if (diff) {
    lines.push('## Changes', '');
    for (const change of diff.changes) lines.push(`- \`${change.summary}\``);
    lines.push('');
  }
  if (plan) {
    lines.push(`## Plan (${plan.route.toLowerCase()} route)`, '');
    lines.push(plan.sql.trim() === '' ? 'Nothing to run.' : fence(plan.sql), '');
    for (const item of plan.unplanned) lines.push(`- Not in the plan: \`${item.change}\`: ${item.reason}`);
    if (plan.unplanned.length > 0) lines.push('');
  }
  return lines.join('\n');
}

/** A checked migration, statement by statement. */
export function lintMarkdown(lint: LintResponse): string {
  const lines = ['# SchemaGuard migration check', '', ...scoreLines(lint.score)];
  const problems = lint.diagnostics.filter((diagnostic) => diagnostic.input === 'migration');
  lint.statements.forEach((statement, index) => {
    lines.push(`## Line ${statement.line}`, '', fence(statement.sql), '');
    if (statement.status !== 'ANALYSED') lines.push(`Status: ${sentenceCase(statement.status)}`, '');
    for (const problem of problems) {
      if (owner(lint.statements, problem) === index) lines.push(`Error: ${problem.message}`, '');
    }
    for (const finding of ordered(statement.findings)) lines.push(...findingLines(finding, '###'));
  });
  return lines.join('\n');
}

import { BadInput } from './attachments.ts';

export type Command = { name: 'clear' | 'compact' | 'model' | 'effort'; argument: string };

export function parseCommand(body: Record<string, unknown>): Command {
  const { name, argument = '' } = body;
  if (typeof argument !== 'string') throw new BadInput();
  switch (name) {
    case 'clear':
      if (argument !== '') throw new BadInput();
      break;
    case 'compact':
      if ([...argument].length > 10_000) throw new BadInput();
      break;
    case 'model':
    case 'effort':
      if (!argument.trim() || argument.length > 512) throw new BadInput();
      break;
    default:
      throw new BadInput();
  }
  return { name, argument };
}

type Choice = { value: string; label: string };
type Model = { id: string; label: string; efforts: Choice[]; isDefault?: boolean };
export type SessionOptions = {
  models: Model[]; current: { model?: string; effort?: string };
  conversationCommands?: string[]; compactInstructions?: boolean;
};
type ReportedCommand = { name: string; description?: string; kind?: string; argumentHint?: string; path?: string };
export type CommandReport = { commands?: ReportedCommand[]; commandsPending?: boolean };

export function isReportedSkill(row: ReportedCommand) {
  return row.kind === 'skill' || !!row.path;
}

export function commandMenu(report: CommandReport, session: SessionOptions) {
  const commands: { name: string; description: string; kind: string; argumentHint: string | null }[] = [];
  const skills: { name: string; description: string; path: string | null; argumentHint: string | null }[] = [];
  const options: Partial<Record<'model' | 'effort', { current: string | null; choices: Choice[] }>> = {};
  for (const name of session.conversationCommands ?? []) {
    if (name === 'clear' || name === 'compact') commands.push({
      name, kind: 'conversation', description: name === 'clear' ? '会話をリセット' : '会話を要約して圧縮',
      argumentHint: name === 'compact' && session.compactInstructions ? '[指示]' : null
    });
  }
  const models = session.models.filter(model => model.id.trim());
  if (models.length) {
    options.model = { current: session.current.model || null, choices: models.map(m => ({ value: m.id, label: m.label })) };
    commands.push({ name: 'model', kind: 'option', description: 'モデルを変更', argumentHint: '<モデル>' });
    const model = session.current.model ? models.find(m => m.id === session.current.model) : models.find(m => m.isDefault);
    const efforts = model?.efforts?.filter(choice => choice.value.trim());
    if (efforts?.length) {
      options.effort = { current: session.current.effort || null, choices: efforts.map(({ value, label }) => ({ value, label })) };
      commands.push({ name: 'effort', kind: 'option', description: '推論の強さを変更', argumentHint: '<強さ>' });
    }
  }
  for (const row of report.commands ?? []) {
    const entry = { name: row.name, description: row.description ?? '', argumentHint: row.argumentHint ?? null };
    if (isReportedSkill(row)) skills.push({ ...entry, path: row.path ?? null });
    else if (!['clear', 'compact', 'model', 'effort'].includes(row.name)) commands.push({ ...entry, kind: 'text' });
  }
  return { commands, skills, options };
}

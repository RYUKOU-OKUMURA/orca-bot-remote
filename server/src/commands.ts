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
type Model = {
  id: string; label: string; efforts: Choice[]; isDefault?: boolean;
  resolvedModel?: string; defaultEffort?: string; declaredDefaultEffort?: string;
};
export type SessionOptions = {
  models: Model[]; current?: { model?: string; effort?: string };
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
  const { models, current } = dedupeModels(session.models, session.current?.model);
  if (models.length) {
    options.model = { current: current || null, choices: models.map(m => ({ value: m.id, label: m.label })) };
    commands.push({ name: 'model', kind: 'option', description: 'モデルを変更', argumentHint: '<モデル>' });
    const model = current ? models.find(m => m.id === current) : models.find(m => m.isDefault);
    const efforts = model?.efforts?.filter(choice => choice.value.trim());
    if (efforts?.length) {
      options.effort = { current: session.current?.effort || null, choices: efforts.map(({ value, label }) => ({ value, label })) };
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

const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim();
const effortValues = (model: Model) => JSON.stringify((Array.isArray(model.efforts) ? model.efforts : []).map(c => c?.value));

// Orca also lists short aliases (opus -> claude-opus-5-5) under the same label; keep only the full ids.
// A current model that is a dropped alias is mapped to its full id.
export function dedupeModels(list: unknown, current?: string) {
  const rows = (Array.isArray(list) ? list as Model[] : []).filter(model => text(model?.id));
  const ids = new Set(rows.map(model => model.id));
  const models: Model[] = [];
  const promoted = new Set<string>();
  for (const model of rows) {
    if (models.some(kept => kept.id === model.id)) continue;
    const label = text(model.label) ? model.label : model.id;
    const target = typeof model.resolvedModel === 'string' && model.resolvedModel !== model.id && ids.has(model.resolvedModel)
      ? model.resolvedModel
      // Without resolvedModel: a shorter id after an entry with the same label and efforts.
      : model.resolvedModel === undefined && model.isDefault !== true && model.id !== current
        ? models.find(kept => kept.label === label && kept.id.length > model.id.length &&
          effortValues(kept) === effortValues(model))?.id
        : undefined;
    if (target === undefined) {
      models.push({ ...model, label });
      continue;
    }
    if (current === model.id) current = target;
    if (model.isDefault === true) promoted.add(target);
  }
  return {
    models: promoted.size && !models.some(m => m.isDefault === true)
      ? models.map(m => promoted.has(m.id) ? { ...m, isDefault: true } : m) : models,
    current
  };
}

export function modelOptions(session?: Partial<SessionOptions>) {
  const { models: rows, current } = dedupeModels(session?.models, session?.current?.model || undefined);
  const models = rows.map(model => ({
    id: model.id, label: model.label, isDefault: model.isDefault === true,
    efforts: (Array.isArray(model.efforts) ? model.efforts : []).filter(choice => text(choice?.value))
      .map(({ value, label }) => ({ value, label: text(label) ? label : value }))
  }));
  const defaultModel = current || models.find(model => model.isDefault)?.id || null;
  const model = rows.find(row => row.id === defaultModel);
  const effort = [session?.current?.effort, model?.defaultEffort, model?.declaredDefaultEffort].find(text);
  return { models, defaultModel, defaultEffort: effort ?? null };
}

import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import type { ModelResponse } from '../api/client.ts';
import { HEADER, layoutModel, PAD_X, ROW, tableLabel, typeText, type Box } from '../diagram/layout.ts';

type Status = ModelResponse['tables'][number]['status'];

// Full class names, so Tailwind sees them: the change colours of the Changes tab.
const TONE: Record<Status, { stroke: string; text: string; tint: string; tag: string; mark: string }> = {
  ADDED: { stroke: 'stroke-low', text: 'fill-low', tint: 'fill-low/10', tag: 'new', mark: '+' },
  REMOVED: { stroke: 'stroke-critical', text: 'fill-critical', tint: 'fill-critical/10', tag: 'dropped', mark: '−' },
  CHANGED: { stroke: 'stroke-medium', text: 'fill-medium', tint: 'fill-medium/10', tag: 'changed', mark: '~' },
  UNCHANGED: { stroke: 'stroke-line', text: 'fill-muted', tint: 'fill-transparent', tag: '', mark: '' },
};

const LEGEND: { status: Status; label: string; swatch: string }[] = [
  { status: 'ADDED', label: 'Added', swatch: 'border-low bg-low/10' },
  { status: 'REMOVED', label: 'Removed', swatch: 'border-critical bg-critical/10' },
  { status: 'CHANGED', label: 'Changed', swatch: 'border-medium bg-medium/10' },
  { status: 'UNCHANGED', label: 'Unchanged', swatch: 'border-line bg-surface' },
];

interface View {
  x: number;
  y: number;
  w: number;
  h: number;
}

function summary(model: ModelResponse): string {
  const count = (status: Status) => model.tables.filter((table) => table.status === status).length;
  const parts = [`${model.tables.length} tables`, `${count('ADDED')} added`, `${count('REMOVED')} removed`, `${count('CHANGED')} changed`];
  return `Diagram of ${parts.join(', ')}, and ${model.references.length} foreign keys. The Changes tab lists every change as text.`;
}

function TableBox({ box }: { box: Box }) {
  const tone = TONE[box.table.status];
  return (
    <g transform={`translate(${box.x} ${box.y})`} data-table={box.key}>
      <rect
        width={box.width}
        height={box.height}
        rx={6}
        className={`fill-surface ${tone.stroke}`}
        strokeWidth={box.table.status === 'UNCHANGED' ? 1 : 2}
      />
      <rect x={1} y={1} width={box.width - 2} height={HEADER - 1} rx={5} className={tone.tint} />
      <line x1={0} x2={box.width} y1={HEADER} y2={HEADER} className="stroke-line" />
      <text x={PAD_X} y={22} className="fill-ink text-[13px] font-semibold">
        {tableLabel(box.table)}
      </text>
      {tone.tag !== '' && (
        <text x={box.width - PAD_X} y={22} textAnchor="end" className={`${tone.text} text-[11px] font-semibold`}>
          {tone.tag}
        </text>
      )}
      {box.table.columns.map((column, index) => {
        const columnTone = TONE[column.status];
        const y = HEADER + index * ROW;
        const removed = column.status === 'REMOVED';
        return (
          <g key={column.name}>
            {column.status !== 'UNCHANGED' && (
              <rect x={1} y={y + 1} width={box.width - 2} height={ROW - 2} className={columnTone.tint} />
            )}
            <text
              x={PAD_X}
              y={y + 15}
              className={`font-mono text-[12px] ${column.status === 'UNCHANGED' ? 'fill-ink' : columnTone.text}`}
              textDecoration={removed ? 'line-through' : undefined}
            >
              {columnTone.mark !== '' && `${columnTone.mark} `}
              {column.name}
              {column.primaryKey && <tspan className="fill-elephant font-sans text-[10px] font-semibold"> PK</tspan>}
            </text>
            <text
              x={box.width - PAD_X}
              y={y + 15}
              textAnchor="end"
              className="fill-muted font-mono text-[12px]"
              textDecoration={removed ? 'line-through' : undefined}
            >
              {typeText(column)}
              {column.notNull ? '' : '?'}
            </text>
          </g>
        );
      })}
    </g>
  );
}

/** Both schemas as one diagram: tables, columns and foreign keys in the change colours. Drag to pan. */
export default function ErDiagram({ model }: { model: ModelResponse }) {
  const layout = useMemo(() => layoutModel(model), [model]);
  const fit = useMemo<View>(() => ({ x: 0, y: 0, w: layout.width, h: layout.height }), [layout]);
  const [view, setView] = useState<View>(fit);
  // A new model (another analysis in the same tab) starts from its own fitted view.
  const [shown, setShown] = useState(fit);
  if (shown !== fit) {
    setShown(fit);
    setView(fit);
  }
  const svg = useRef<SVGSVGElement | null>(null);
  const drag = useRef<{ pointer: number; clientX: number; clientY: number; view: View } | null>(null);

  function zoom(factor: number) {
    setView((current) => {
      const w = Math.min(layout.width * 4, Math.max(layout.width * 0.15, current.w * factor));
      const h = (w / current.w) * current.h;
      return { x: current.x + (current.w - w) / 2, y: current.y + (current.h - h) / 2, w, h };
    });
  }

  // Ctrl or Cmd with the wheel zooms; a plain wheel keeps scrolling the page. React's wheel
  // listener is passive and cannot prevent the browser's own zoom, so this one is added by hand.
  useEffect(() => {
    const element = svg.current;
    if (!element) return;
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      zoom(event.deltaY > 0 ? 1.15 : 1 / 1.15);
    };
    element.addEventListener('wheel', onWheel, { passive: false });
    return () => {
      element.removeEventListener('wheel', onWheel);
    };
  });

  function onPointerDown(event: PointerEvent<SVGSVGElement>) {
    event.currentTarget.setPointerCapture(event.pointerId);
    drag.current = { pointer: event.pointerId, clientX: event.clientX, clientY: event.clientY, view };
  }

  function onPointerMove(event: PointerEvent<SVGSVGElement>) {
    const start = drag.current;
    if (start?.pointer !== event.pointerId) return;
    const rect = event.currentTarget.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) return;
    const scale = Math.max(start.view.w / rect.width, start.view.h / rect.height);
    setView({
      ...start.view,
      x: start.view.x - (event.clientX - start.clientX) * scale,
      y: start.view.y - (event.clientY - start.clientY) * scale,
    });
  }

  function onKeyDown(event: KeyboardEvent<SVGSVGElement>) {
    const step = 0.1;
    const moves: Record<string, () => void> = {
      '+': () => {
        zoom(1 / 1.2);
      },
      '=': () => {
        zoom(1 / 1.2);
      },
      '-': () => {
        zoom(1.2);
      },
      '0': () => {
        setView(fit);
      },
      ArrowLeft: () => {
        setView((v) => ({ ...v, x: v.x - v.w * step }));
      },
      ArrowRight: () => {
        setView((v) => ({ ...v, x: v.x + v.w * step }));
      },
      ArrowUp: () => {
        setView((v) => ({ ...v, y: v.y - v.h * step }));
      },
      ArrowDown: () => {
        setView((v) => ({ ...v, y: v.y + v.h * step }));
      },
    };
    const move = moves[event.key];
    if (move) {
      event.preventDefault();
      move();
    }
  }

  const button = 'rounded-md border border-line bg-surface px-2.5 py-1 text-sm font-medium hover:border-elephant';

  if (model.tables.length === 0) {
    return <p className="text-sm text-muted">Neither schema has a table to draw.</p>;
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <ul className="flex flex-wrap gap-x-4 gap-y-1 text-sm" aria-label="Colours">
          {LEGEND.map((item) => (
            <li key={item.status} className="flex items-center gap-1.5">
              <span className={`inline-block h-3 w-4 rounded-sm border-2 ${item.swatch}`} aria-hidden="true" />
              {item.label}
            </li>
          ))}
        </ul>
        <div className="flex gap-1.5">
          <button
            type="button"
            className={button}
            aria-label="Zoom in"
            onClick={() => {
              zoom(1 / 1.2);
            }}
          >
            +
          </button>
          <button
            type="button"
            className={button}
            aria-label="Zoom out"
            onClick={() => {
              zoom(1.2);
            }}
          >
            −
          </button>
          <button
            type="button"
            className={button}
            onClick={() => {
              setView(fit);
            }}
          >
            Fit
          </button>
        </div>
      </div>
      <svg
        ref={svg}
        role="img"
        aria-label={summary(model)}
        aria-describedby="diagram-help"
        tabIndex={0}
        viewBox={`${view.x} ${view.y} ${view.w} ${view.h}`}
        preserveAspectRatio="xMidYMid meet"
        className="h-[28rem] w-full cursor-grab touch-none select-none rounded-md border border-line bg-paper active:cursor-grabbing sm:h-[34rem]"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={() => {
          drag.current = null;
        }}
        onPointerCancel={() => {
          drag.current = null;
        }}
        onKeyDown={onKeyDown}
      >
        <defs>
          {(Object.keys(TONE) as Status[]).map((status) => (
            <marker
              key={status}
              id={`arrow-${status}`}
              viewBox="0 0 10 10"
              refX={9}
              refY={5}
              markerWidth={7}
              markerHeight={7}
              orient="auto-start-reverse"
            >
              <path d="M 0 0 L 10 5 L 0 10 z" className={status === 'UNCHANGED' ? 'fill-muted' : TONE[status].text} />
            </marker>
          ))}
        </defs>
        {layout.links.map((link) => (
          <path
            key={link.key}
            d={link.path}
            fill="none"
            strokeWidth={link.reference.status === 'UNCHANGED' ? 1.25 : 2}
            strokeDasharray={link.reference.status === 'REMOVED' ? '6 4' : undefined}
            className={link.reference.status === 'UNCHANGED' ? 'stroke-muted' : TONE[link.reference.status].stroke}
            markerEnd={`url(#arrow-${link.reference.status})`}
            data-reference={link.reference.name ?? undefined}
          />
        ))}
        {layout.boxes.map((box) => (
          <TableBox key={box.key} box={box} />
        ))}
      </svg>
      <p id="diagram-help" className="text-sm text-muted">
        Arrows point from a foreign key to the table it references; a ? after a type means NULL is allowed. Drag to
        move, Ctrl and the wheel or the buttons to zoom; with the diagram focused, arrow keys move, + and − zoom, 0 fits.
      </p>
    </div>
  );
}

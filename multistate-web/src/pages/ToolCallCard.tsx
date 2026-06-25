import type { ReactElement } from 'react';
import type { ToolInvocation } from 'ai';

interface ToolCallCardProps {
  readonly invocation: ToolInvocation;
}

export function ToolCallCard({ invocation }: ToolCallCardProps): ReactElement {
  return (
    <aside aria-label="tool-call" data-tool-name={invocation.toolName}>
      <header>
        called <code>{invocation.toolName}</code>
      </header>
      <pre data-testid="tool-args">
        {JSON.stringify(invocation.args, null, 2)}
      </pre>
      {invocation.state === 'result' && (
        <pre data-testid="tool-result">
          {JSON.stringify(invocation.result, null, 2)}
        </pre>
      )}
    </aside>
  );
}

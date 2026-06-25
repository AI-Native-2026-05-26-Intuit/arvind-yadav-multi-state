import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ToolInvocation } from 'ai';
import { ToolCallCard } from '../pages/ToolCallCard';

describe('ToolCallCard', () => {
  it('renders an aside with aria-label="tool-call"', () => {
    const inv: ToolInvocation = {
      state:      'call',
      toolCallId: 'call-1',
      toolName:   'lookupTenant',
      args:       { id: 'stub-id' },
    };
    render(<ToolCallCard invocation={inv} />);
    expect(screen.getByRole('complementary', { name: 'tool-call' })).toBeInTheDocument();
  });

  it('renders the tool name', () => {
    const inv: ToolInvocation = {
      state:      'call',
      toolCallId: 'call-2',
      toolName:   'nexusForState',
      args:       { state: 'CA' },
    };
    render(<ToolCallCard invocation={inv} />);
    expect(screen.getByText('nexusForState')).toBeInTheDocument();
  });

  it('renders the call args as JSON', () => {
    const inv: ToolInvocation = {
      state:      'call',
      toolCallId: 'call-3',
      toolName:   'lookupTenant',
      args:       { id: 'tenant-7' },
    };
    render(<ToolCallCard invocation={inv} />);
    expect(screen.getByTestId('tool-args')).toHaveTextContent('"id"');
    expect(screen.getByTestId('tool-args')).toHaveTextContent('"tenant-7"');
  });

  it('partial-call state: no result panel is rendered', () => {
    const inv: ToolInvocation = {
      state:      'partial-call',
      toolCallId: 'call-4',
      toolName:   'lookupTenant',
      args:       { id: 'incomplete' },
    };
    render(<ToolCallCard invocation={inv} />);
    expect(screen.queryByTestId('tool-result')).not.toBeInTheDocument();
  });

  it('call state: no result panel is rendered', () => {
    const inv: ToolInvocation = {
      state:      'call',
      toolCallId: 'call-5',
      toolName:   'nexusForState',
      args:       { state: 'NY' },
    };
    render(<ToolCallCard invocation={inv} />);
    expect(screen.queryByTestId('tool-result')).not.toBeInTheDocument();
  });

  it('result state: renders the result payload under data-testid="tool-result"', () => {
    const inv: ToolInvocation = {
      state:      'result',
      toolCallId: 'call-6',
      toolName:   'lookupTenant',
      args:       { id: 'tenant-9' },
      result:     { id: 'tenant-9', name: 'stub tenant', state: 'CA' },
    };
    render(<ToolCallCard invocation={inv} />);
    const out = screen.getByTestId('tool-result');
    expect(out).toBeInTheDocument();
    expect(out).toHaveTextContent('"stub tenant"');
    expect(out).toHaveTextContent('"CA"');
  });

  it('result state: exposes the tool name via data-tool-name attribute', () => {
    const inv: ToolInvocation = {
      state:      'result',
      toolCallId: 'call-7',
      toolName:   'nexusForState',
      args:       { state: 'TX' },
      result:     [],
    };
    render(<ToolCallCard invocation={inv} />);
    expect(
      screen.getByRole('complementary', { name: 'tool-call' }),
    ).toHaveAttribute('data-tool-name', 'nexusForState');
  });
});

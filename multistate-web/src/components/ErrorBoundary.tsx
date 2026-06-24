import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';

type Props = {
  readonly children: ReactNode;
  readonly fallback: (error: Error, reset: () => void) => ReactNode;
};

type State = { readonly error: Error | null };

export class ErrorBoundary extends Component<Props, State> {
  // `key`-based reset bumps when the user clicks retry; the bump remounts
  // the children subtree so a transiently-bad component starts clean.
  override state: State = { error: null };
  private resetKey = 0;

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('[ErrorBoundary] caught error', error, info.componentStack);
  }

  private reset = (): void => {
    this.resetKey += 1;
    this.setState({ error: null });
  };

  override render(): ReactNode {
    const { error } = this.state;
    if (error !== null) {
      return this.props.fallback(error, this.reset);
    }
    // Keying on resetKey forces a fresh mount of descendants when the
    // user retries — otherwise a stale render from before the throw
    // could persist.
    return <div key={this.resetKey}>{this.props.children}</div>;
  }
}

import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  /** Shown instead of the children when they fail to load or render. */
  fallback: ReactNode;
  children: ReactNode;
}

interface State {
  failed: boolean;
}

/**
 * Catches a lazily loaded part (the code editor, the diagram) that fails to download or render, so
 * a flaky network costs that part only and not the whole page.
 */
export class LoadBoundary extends Component<Props, State> {
  override state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('A part of the page failed to load', error, info.componentStack);
  }

  override render(): ReactNode {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}

import type { Tenant } from '../types/tenant';

export type DetailState =
  | { status: 'idle'                       }
  | { status: 'loading'                    }
  | { status: 'success'; data:  Tenant     }
  | { status: 'error';   error: string     }
  | { status: 'empty'                      };

export type DetailAction =
  | { type: 'fetch/start'                  }
  | { type: 'fetch/success'; payload: Tenant | null }
  | { type: 'fetch/error';   error:   string }
  | { type: 'reset'                        };

export const INITIAL_DETAIL_STATE: DetailState = { status: 'idle' };

export function detailReducer(_state: DetailState, action: DetailAction): DetailState {
  switch (action.type) {
    case 'fetch/start':
      return { status: 'loading' };
    case 'fetch/success':
      return action.payload === null
        ? { status: 'empty' }
        : { status: 'success', data: action.payload };
    case 'fetch/error':
      return { status: 'error', error: action.error };
    case 'reset':
      return INITIAL_DETAIL_STATE;
    default: {
      // Exhaustiveness check: if a new action variant is added but a case
      // is missed, this assignment fails to compile (noUnusedLocals would
      // reject a bare `const _ = ...`, so we reference it on the next line).
      const _exhaustive: never = action;
      return _exhaustive;
    }
  }
}

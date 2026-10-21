import type { MutateOptions, UseMutationResult } from '@tanstack/react-query';
import { useRef } from 'react';

/**
 * `mutate` that ignores calls while one is already in flight. A fast double click fires twice before
 * React re-renders the button as loading, and for some actions a second request is not harmless: it
 * would create a second API key nobody sees, or rotate a signing secret twice and drop the old secret
 * the receiver still uses.
 */
export function useSingleFlight<TData, TError, TVariables, TContext>(mutation: UseMutationResult<TData, TError, TVariables, TContext>) {
  const inFlight = useRef(false);
  return (variables: TVariables, options?: MutateOptions<TData, TError, TVariables, TContext>) => {
    if (inFlight.current) return;
    inFlight.current = true;
    mutation.mutate(variables, {
      ...options,
      onSettled: (...args) => {
        inFlight.current = false;
        return options?.onSettled?.(...args);
      },
    });
  };
}

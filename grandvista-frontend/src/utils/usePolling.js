import { useEffect, useRef } from 'react';

/** Calls `callback` every `intervalMs` while `active` is true (always uses the latest callback). */
export function usePolling(callback, active, intervalMs = 4000) {
  const latest = useRef(callback);
  latest.current = callback;
  useEffect(() => {
    if (!active) return undefined;
    const timer = setInterval(() => latest.current(), intervalMs);
    return () => clearInterval(timer);
  }, [active, intervalMs]);
}

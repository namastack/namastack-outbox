import React, { useEffect, useRef, useState, useCallback } from 'react';

declare global {
  interface Window {
    VANTA?: { TOPOLOGY?: (opts: any) => any };
    THREE?: any;
  }
}

const CDN_P5    = '/vendor/p5.min.js';
const CDN_THREE = '/vendor/three.min.js';
const CDN_VANTA = '/vendor/vanta.topology.min.js';

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    if (typeof document === 'undefined') return resolve();
    if (document.querySelector(`script[src="${src}"]`)) {
      resolve();
      return;
    }
    const script = document.createElement('script');
    script.src = src;
    script.async = true;
    script.onload  = () => resolve();
    script.onerror = () => reject(new Error(`Failed to load script: ${src}`));
    document.head.appendChild(script);
  });
}

const RESIZE_DEBOUNCE_MS = 200;

interface VantaBackgroundProps {
  children?: React.ReactNode;
  options?: Record<string, unknown>;
  style?: React.CSSProperties;
  className?: string;
  minHeight?: string;
}

export default function VantaBackground({
  children,
  options = {},
  style = {},
  className = '',
  minHeight = '10vh',
}: VantaBackgroundProps) {
  const containerRef  = useRef<HTMLDivElement>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const vantaRef      = useRef<any>(null);
  const resizeTimer   = useRef<ReturnType<typeof setTimeout> | null>(null);
  const optionsRef    = useRef(options);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Determine whether to enable Vanta based on user preferences / device
  const shouldEnableVanta = () => {
    try {
      // If the options explicitly set force: true, allow Vanta
      if ((optionsRef.current as any)?.force === true) return true;
      // Respect reduced-motion
      if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false;
      // Respect Save-Data
      // @ts-ignore
      if (navigator && (navigator as any).connection && (navigator as any).connection.saveData) return false;
      // Skip on narrow viewports (mobile) to improve LCP
      if (window.innerWidth && window.innerWidth < 768) return false;
    } catch (e) {
      // If any checks fail, default to enabling Vanta
      return true;
    }
    return true;
  }

  useEffect(() => {
    optionsRef.current = options;
  }, [options]);

  useEffect(() => {
    let cancelled = false;
    async function loadDeps() {
      try {
        // Only load scripts if Vanta should run
        if (!shouldEnableVanta()) {
          if (!cancelled) setReady(false);
          return;
        }

        await loadScript(CDN_THREE);
        await loadScript(CDN_P5);
        await loadScript(CDN_VANTA);

        if (!cancelled) setReady(true);
      } catch (err: unknown) {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err));
      }
    }
    loadDeps();
    return () => { cancelled = true; };
  }, []);

  const initVanta = useCallback(() => {
    if (!containerRef.current || !window.VANTA?.TOPOLOGY) return;

    if (vantaRef.current) {
      try { vantaRef.current.destroy(); } catch (_) {}
      vantaRef.current = null;
    }

    try {
      vantaRef.current = window.VANTA.TOPOLOGY({
        el:              containerRef.current,
        mouseControls:   false,
        touchControls:   false,
        gyroControls:    false,
        minHeight:       200.0,
        minWidth:        200.0,
        scale:           1.0,
        scaleMobile:     1.0,
        color:           0x3b82f6,
        backgroundColor: 0x0f172a,
        ...optionsRef.current,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    if (!ready) return;
    initVanta();
    return () => {
      if (vantaRef.current) {
        try { vantaRef.current.destroy(); } catch (_) {}
        vantaRef.current = null;
      }
    };
  }, [ready, initVanta]);

  useEffect(() => {
    if (!ready) return;

    function onResize() {
      if (vantaRef.current) {
        try { vantaRef.current.destroy(); } catch (_) {}
        vantaRef.current = null;
      }

      if (resizeTimer.current) clearTimeout(resizeTimer.current);
      resizeTimer.current = setTimeout(() => {
        initVanta();
      }, RESIZE_DEBOUNCE_MS);
    }

    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      if (resizeTimer.current) clearTimeout(resizeTimer.current);
    };
  }, [ready, initVanta]);

  return (
    <div
      ref={containerRef}
      className={className}
      style={{
        position: 'relative',
        minHeight,
        width: '100%',
        overflow: 'hidden',
        ...style,
      }}
    >
      {error && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#f87171',
            fontSize: '0.875rem',
            fontFamily: 'monospace',
            padding: '1rem',
            background: 'rgba(0,0,0,0.75)',
            zIndex: 10,
          }}
        >
          ⚠️ Vanta failed to load: {error}
        </div>
      )}

      <div style={{ position: 'relative', zIndex: 1, width: '100%', height: '100%' }}>
        {children}
      </div>
    </div>
  );
}

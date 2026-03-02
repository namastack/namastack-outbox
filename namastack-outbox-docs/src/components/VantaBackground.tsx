import React, { useEffect, useRef, useState, useCallback } from 'react';

/**
 * VantaBackground
 *
 * Docusaurus-compatible Vanta.js TOPOLOGY background component.
 * - Scripts loaded client-side only (via useEffect)
 * - Handles window resize by debounced destroy + reinit to avoid p5 canvas errors
 *
 * Props:
 *   children    – content rendered on top of the animation
 *   options     – Vanta TOPOLOGY options to override defaults
 *   style       – additional styles for the wrapper div
 *   className   – additional class names for the wrapper div
 *   minHeight   – minimum height of the container (default: '100vh')
 */

const CDN_P5    = 'https://cdnjs.cloudflare.com/ajax/libs/p5.js/2.0.5/p5.min.js';
const CDN_VANTA = 'https://cdn.jsdelivr.net/npm/vanta@0.5.24/dist/vanta.topology.min.js';

function loadScript(src) {
  return new Promise((resolve, reject) => {
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

export default function VantaBackground({
  children,
  options = {},
  style = {},
  className = '',
  minHeight = '10vh',
}) {
  const containerRef  = useRef(null);
  const vantaRef      = useRef(null);
  const resizeTimer   = useRef(null);
  const optionsRef    = useRef(options);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    optionsRef.current = options;
  }, [options]);

  useEffect(() => {
    let cancelled = false;
    async function loadDeps() {
      try {
        await loadScript(CDN_P5);
        await loadScript(CDN_VANTA);
        if (!cancelled) setReady(true);
      } catch (err) {
        if (!cancelled) setError(err.message);
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

      clearTimeout(resizeTimer.current);
      resizeTimer.current = setTimeout(() => {
        initVanta();
      }, RESIZE_DEBOUNCE_MS);
    }

    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      clearTimeout(resizeTimer.current);
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

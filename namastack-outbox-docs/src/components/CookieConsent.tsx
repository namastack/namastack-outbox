import React, { useEffect, useRef } from 'react';
import pluginConfig from './CookieConsentConfig';

const GA_ID = 'G-7T0WYS15SK';

const CookieConsentComponent = () => {
  const ccRef = useRef<any>(null);
  const gaLoadedRef = useRef(false);

  useEffect(() => {
    import('vanilla-cookieconsent').then((CookieConsent) => {
      ccRef.current = CookieConsent;

      const handleConsentChange = () => {
        if (CookieConsent.acceptedCategory('analytics')) {
          enableGA();
        } else {
          disableGA(CookieConsent);
        }
      };

      CookieConsent.run({
        ...pluginConfig,
        onConsent: handleConsentChange,
        onChange: handleConsentChange,
      });
    });
  }, []);

  const enableGA = () => {
    window[`ga-disable-${GA_ID}`] = false;

    if (gaLoadedRef.current) return;
    gaLoadedRef.current = true;

    window.dataLayer = window.dataLayer || [];
    window.gtag = function(){ window.dataLayer.push(arguments); };
    window.gtag('js', new Date());
    window.gtag('config', GA_ID);

    const script = document.createElement('script');
    script.src = `https://www.googletagmanager.com/gtag/js?id=${GA_ID}`;
    script.async = true;
    document.head.appendChild(script);
  };

  const disableGA = (CookieConsent: any) => {
    window[`ga-disable-${GA_ID}`] = true;

    CookieConsent.eraseCookies(/^(?!cc_cookie$)/, '/', '.namastack.io');
  };

  return (
    <a
      href="#"
      onClick={(e) => {
        e.preventDefault();
        ccRef.current?.showPreferences();
      }}
    >
      Show Cookie Preferences
    </a>
  );
};

export default CookieConsentComponent;

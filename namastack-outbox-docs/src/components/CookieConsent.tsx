import React, { useState, useEffect, useRef } from 'react';
import pluginConfig from './CookieConsentConfig';

const GA_ID = 'G-7T0WYS15SK';

const CookieConsentComponent = () => {
  const [enableGA, setEnableGA] = useState(false);
  const ccRef = useRef(null);

  useEffect(() => {
    import('vanilla-cookieconsent').then((CookieConsent) => {
      ccRef.current = CookieConsent;

      const handleConsentChange = () => {
        if (CookieConsent.acceptedCategory("analytics")) {
          window[`ga-disable-${GA_ID}`] = false;
          setEnableGA(true);
        } else {
          window[`ga-disable-${GA_ID}`] = true;
          setEnableGA(false);
          CookieConsent.eraseCookies(/^(?!cc_cookie$)/);
        }
      };

      CookieConsent.run({
        ...pluginConfig,
        onConsent: handleConsentChange,
        onChange: handleConsentChange,
      });
    });
  }, []);

  return (
    <>
      {enableGA && (
        <>
          <script async src={`https://www.googletagmanager.com/gtag/js?id=${GA_ID}`} />
          <script dangerouslySetInnerHTML={{ __html: `
            window.dataLayer = window.dataLayer || [];
            function gtag(){dataLayer.push(arguments);}
            gtag('js', new Date());
            gtag('config', '${GA_ID}');
          `}} />
        </>
      )}
      <a href="#" onClick={() => ccRef.current?.showPreferences()}>
        Show Cookie Preferences
      </a>
    </>
  );
};

export default CookieConsentComponent;

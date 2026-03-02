import type { CookieConsentConfig } from 'vanilla-cookieconsent';

const pluginConfig: CookieConsentConfig = {
  autoShow: true,
  guiOptions: {
      consentModal: {
          layout: "box",
          position: "bottom left",
          equalWeightButtons: true,
          flipButtons: false
      },
      preferencesModal: {
          layout: "box",
          position: "right",
          equalWeightButtons: true,
          flipButtons: false
      }
  },
  categories: {
      necessary: {
          readOnly: true
      },
      analytics: {}
  },
  language: {
      default: "en",
      autoDetect: "browser",
      translations: {
          de: {
              consentModal: {
                  title: "Willkommen bei Namastack - Cookies",
                  description: "Wir verwenden notwendige Cookies, damit diese Website sicher und funktional bleibt. Du kannst zusätzlich zustimmen, dass wir anonyme Nutzungsdaten zur Verbesserung der Seite sammeln.",
                  acceptAllBtn: "Alle akzeptieren",
                  acceptNecessaryBtn: "Nur notwendige Cookies",
                  showPreferencesBtn: "Einstellungen verwalten",
              },
              preferencesModal: {
                  title: "Zustimmungs-Einstellungen",
                  acceptAllBtn: "Alle akzeptieren",
                  acceptNecessaryBtn: "Nur notwendige Cookies",
                  savePreferencesBtn: "Einstellungen speichern",
                  closeIconLabel: "Modal schließen",
                  serviceCounterLabel: "Dienst|Dienste",
                  sections: [
                      {
                          title: "Warum wir Cookies verwenden",
                          description: "Cookies helfen uns, die Kernfunktionen der Website bereitzustellen, Fehler zu erkennen und die Benutzerfreundlichkeit zu verbessern. Wir speichern keine personenbezogenen Daten ohne deine Zustimmung."
                      },
                      {
                          title: "Streng notwendige Cookies <span class=\"pm__badge\">Immer aktiviert</span>",
                          description: "Diese Cookies sind für grundlegende Funktionen wie Seitennavigation und Sicherheit erforderlich. Ohne diese können Teile der Website nicht funktionieren.",
                          linkedCategory: "necessary"
                      },
                      {
                          title: "Analytische Cookies",
                          description: "Mit deiner Zustimmung sammeln wir anonyme Nutzungsdaten (z. B. Seitenaufrufe, Verweildauer), um Inhalte und Performance zu verbessern. Diese Daten werden nicht genutzt, um dich persönlich zu identifizieren.",
                          linkedCategory: "analytics"
                      }
                  ]
              }
          },
          en: {
              consentModal: {
                  title: "Welcome to Namastack - Cookies",
                  description: "We use essential cookies to keep this site secure and functional. With your permission we also collect anonymous usage data to improve content and performance.",
                  acceptAllBtn: "Accept all",
                  acceptNecessaryBtn: "Only necessary cookies",
                  showPreferencesBtn: "Manage preferences",
              },
              preferencesModal: {
                  title: "Consent Preferences",
                  acceptAllBtn: "Accept all",
                  acceptNecessaryBtn: "Only necessary cookies",
                  savePreferencesBtn: "Save preferences",
                  closeIconLabel: "Close modal",
                  serviceCounterLabel: "Service|Services",
                  sections: [
                      {
                          title: "Why we use cookies",
                          description: "Cookies enable core site functionality, help us detect issues and improve the user experience. We do not store personal information without your consent."
                      },
                      {
                          title: "Strictly Necessary Cookies <span class=\"pm__badge\">Always enabled</span>",
                          description: "These cookies are required for basic functions such as navigation and security. Without them parts of the site may not work.",
                          linkedCategory: "necessary"
                      },
                      {
                          title: "Analytics Cookies",
                          description: "With your consent we collect anonymous usage information (e.g. page views, session length) to improve content and performance. These analytics do not identify you personally.",
                          linkedCategory: "analytics"
                      }
                  ]
              }
          }
      }
  }
};

export default pluginConfig;

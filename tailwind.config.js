/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: "class",
  content: [
    "./src/main/resources/templates/**/*.html",
    "./src/main/resources/static/js/**/*.js"
  ],
  theme: {
    extend: {
      colors: {
        "primary": "var(--primary)",
        "primary-container": "var(--primary-container)",
        "on-primary": "var(--on-primary)",
        "on-primary-container": "var(--on-primary-container)",
        "primary-fixed": "var(--primary-fixed)",
        "primary-fixed-dim": "var(--primary-fixed-dim)",
        "on-primary-fixed": "var(--on-primary-fixed)",
        "secondary": "var(--secondary)",
        "secondary-container": "var(--secondary-container)",
        "on-secondary": "var(--on-secondary)",
        "on-secondary-container": "var(--on-secondary-container)",
        "secondary-fixed": "var(--secondary-fixed)",
        "secondary-fixed-dim": "var(--secondary-fixed-dim)",
        "on-secondary-fixed": "var(--on-secondary-fixed)",
        "tertiary": "var(--tertiary)",
        "tertiary-container": "var(--tertiary-container)",
        "tertiary-fixed": "var(--tertiary-fixed)",
        "tertiary-fixed-dim": "var(--tertiary-fixed-dim)",
        "surface": "var(--surface)",
        "background": "var(--background)",
        "surface-bright": "var(--surface-bright)",
        "surface-container-lowest": "var(--surface-container-lowest)",
        "surface-container-low": "var(--surface-container-low)",
        "surface-container": "var(--surface-container)",
        "surface-container-high": "var(--surface-container-high)",
        "surface-container-highest": "var(--surface-container-highest)",
        "on-surface": "var(--on-surface)",
        "on-background": "var(--on-background)",
        "on-surface-variant": "var(--on-surface-variant)",
        "outline": "var(--outline)",
        "outline-variant": "var(--outline-variant)",
        "error": "var(--error)",
        "error-container": "var(--error-container)",
        "on-error-container": "var(--on-error-container)"
      },
      borderRadius: {
        "DEFAULT": "0.5rem",
        "lg": "0.75rem",
        "xl": "1rem",
        "2xl": "1.35rem",
        "3xl": "1.75rem",
        "full": "9999px"
      },
      spacing: {
        "space-2xl": "3rem",
        "space-xl": "2rem",
        "space-lg": "1.5rem",
        "space-sm": "0.75rem",
        "gutter-sm": "1rem",
        "margin-lg": "2.5rem",
        "margin-sm": "1rem",
        "gutter-lg": "2rem",
        "gutter": "1.5rem",
        "space-xxs": "0.25rem",
        "space-xs": "0.5rem",
        "margin": "2rem",
        "space-md": "1rem"
      },
      fontFamily: {
        "sans": ["Plus Jakarta Sans", "Inter", "sans-serif"],
        "body-lg": ["Plus Jakarta Sans", "sans-serif"],
        "data-mono": ["Plus Jakarta Sans", "monospace"],
        "headline-sm": ["Plus Jakarta Sans", "sans-serif"],
        "headline-lg": ["Plus Jakarta Sans", "sans-serif"],
        "headline-md": ["Plus Jakarta Sans", "sans-serif"],
        "title-lg": ["Plus Jakarta Sans", "sans-serif"],
        "body-sm": ["Plus Jakarta Sans", "sans-serif"],
        "title-md": ["Plus Jakarta Sans", "sans-serif"],
        "display-lg-mobile": ["Plus Jakarta Sans", "sans-serif"],
        "label-md": ["Plus Jakarta Sans", "sans-serif"],
        "label-sm": ["Plus Jakarta Sans", "sans-serif"],
        "body-md": ["Plus Jakarta Sans", "sans-serif"],
        "headline-lg-mobile": ["Plus Jakarta Sans", "sans-serif"],
        "display-lg": ["Plus Jakarta Sans", "sans-serif"]
      },
      fontSize: {
        "body-lg": ["16px", { "lineHeight": "26px", "fontWeight": "400" }],
        "data-mono": ["14px", { "lineHeight": "20px", "fontWeight": "500" }],
        "headline-sm": ["20px", { "lineHeight": "28px", "fontWeight": "600" }],
        "headline-lg": ["30px", { "lineHeight": "38px", "fontWeight": "700" }],
        "headline-md": ["24px", { "lineHeight": "32px", "fontWeight": "700" }],
        "title-lg": ["18px", { "lineHeight": "26px", "fontWeight": "700" }],
        "body-sm": ["13px", { "lineHeight": "18px", "fontWeight": "400" }],
        "title-md": ["15px", { "lineHeight": "22px", "fontWeight": "600" }],
        "display-lg-mobile": ["32px", { "lineHeight": "40px", "fontWeight": "700" }],
        "label-md": ["12px", { "lineHeight": "16px", "fontWeight": "600" }],
        "label-sm": ["11px", { "lineHeight": "14px", "fontWeight": "600" }],
        "body-md": ["14px", { "lineHeight": "22px", "fontWeight": "400" }],
        "headline-lg-mobile": ["24px", { "lineHeight": "32px", "fontWeight": "600" }],
        "display-lg": ["44px", { "lineHeight": "52px", "fontWeight": "700" }]
      },
      backdropBlur: {
        "xs": "2px"
      },
      boxShadow: {
        "xs": "0 1px 2px 0 rgb(0 0 0 / 0.05)"
      }
    }
  },
  plugins: []
};

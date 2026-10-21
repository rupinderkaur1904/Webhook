import { createTheme, type MantineColorsTuple } from '@mantine/core';

// Colors from the Figma "Relay tokens" collection. Mantine's dark scheme reads text from dark[0],
// dimmed text from dark[2], borders from dark[4], raised surfaces from dark[5], cards and inputs from
// dark[6] and the page from dark[7], so the dark/* tokens are placed at those indexes.
const dark: MantineColorsTuple = [
  '#f3f4f7', // dark/text
  '#c9cdd8',
  '#a3aaba', // dark/text-secondary
  '#8b93a5', // status/pending
  '#272c38', // dark/border
  '#1b1f2a', // dark/raised
  '#13161e', // dark/surface
  '#0b0d12', // dark/canvas
  '#08090d',
  '#050608',
];

// accent/teal (#2dd4bf) sits at index 4, the shade used in the dark scheme.
const teal: MantineColorsTuple = [
  '#e6fcf8',
  '#c3f5ec',
  '#99eddf',
  '#6ae3d0',
  '#2dd4bf',
  '#1fbfa9',
  '#14a593',
  '#0c8a7b',
  '#077063',
  '#03574d',
];

export const theme = createTheme({
  primaryColor: 'teal',
  primaryShade: { light: 6, dark: 4 },
  // Teal buttons get dark text, as in the design.
  autoContrast: true,
  colors: { dark, teal },
  black: '#0b0d12',
  white: '#ffffff',
  fontFamily: 'Geist, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif',
  fontFamilyMonospace: '"Geist Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
  headings: {
    fontFamily: 'Geist, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif',
    fontWeight: '600',
    sizes: {
      h2: { fontSize: '24px', lineHeight: '1.3' },
      h3: { fontSize: '20px', lineHeight: '1.3' },
      h4: { fontSize: '15px', lineHeight: '1.4' },
    },
  },
  // 5 badges · 8 controls · 12 panels · 14–16 windows and modals.
  radius: { xs: '5px', sm: '6px', md: '8px', lg: '12px', xl: '16px' },
  defaultRadius: 'md',
  components: {
    Card: { defaultProps: { radius: 'lg' } },
    Paper: { defaultProps: { radius: 'lg' } },
    Modal: { defaultProps: { radius: 'xl', overlayProps: { backgroundOpacity: 0.6, blur: 2 } } },
    Badge: { defaultProps: { radius: 'xs' } },
  },
});

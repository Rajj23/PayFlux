// The browser uses same-origin URLs; Next.js rewrites them to API_INTERNAL_URL.
export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || '';

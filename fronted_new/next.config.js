/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  reactStrictMode: false,
  async rewrites() {
    const apiUrl = process.env.API_INTERNAL_URL;
    if (!apiUrl) {
      throw new Error('API_INTERNAL_URL must be configured when building the frontend');
    }
    return [
      { source: '/auth/:path*', destination: `${apiUrl}/auth/:path*` },
      { source: '/api/:path*', destination: `${apiUrl}/api/:path*` },
    ];
  },
};

module.exports = nextConfig;

import Head from 'next/head';
import dynamic from 'next/dynamic';

const ClientApp = dynamic(() => import('../src/App'), {
  ssr: false,
  loading: () => (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100vh', background: '#0a0d14', color: '#6366f1', fontFamily: 'Inter, sans-serif' }}>
      Loading PayFlux...
    </div>
  ),
});

export default function CatchAll() {
  return (
    <>
      <Head>
        <title>PayFlux | Modern Financial Platform</title>
        <meta name="viewport" content="width=device-width, initial-scale=1" />
      </Head>
      <ClientApp />
    </>
  );
}

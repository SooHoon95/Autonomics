// 앱이 쓰는 오늘 단가(유가·충전 요금)를 한곳에서 내려준다.
// 오피넷 일반 키는 하루 300건이라 앱마다 부르면 금방 막힌다. 여기서만 부르고 CDN이 3시간 캐시하므로
// 사용자가 몇 명이든 오피넷 호출은 하루 최대 8번이다(일반 키 한도 하루 300건). 키(OPINET_KEY)는 Netlify 환경 변수에만 둔다.

// 충전 요금은 실시간 공식 API가 없어 고시가 바뀌면 여기만 고친다.
const PUBLIC_FAST = { wonPerKwh: 347.2, label: "환경부 공용 급속(100kW 이상)", effectiveDate: "2022-09-01" };

const PRODUCT = { gasoline: "B027", diesel: "D047" };

async function opinetAverages(key) {
  if (!key) return null;
  try {
    const res = await fetch(`https://www.opinet.co.kr/api/avgAllPrice.do?out=json&code=${encodeURIComponent(key)}`);
    if (!res.ok) return null;
    const oil = (await res.json())?.RESULT?.OIL ?? [];
    const pick = (code) => {
      const row = oil.find((o) => o.PRODCD === code);
      return row ? { wonPerLiter: Number(row.PRICE), date: row.TRADE_DT } : null;
    };
    const fuel = { gasoline: pick(PRODUCT.gasoline), diesel: pick(PRODUCT.diesel) };
    return fuel.gasoline || fuel.diesel ? fuel : null;
  } catch {
    return null;
  }
}

export default async () => {
  const fuel = await opinetAverages(Netlify.env.get("OPINET_KEY"));
  const body = { fuel, publicFast: PUBLIC_FAST, fetchedAt: new Date().toISOString() };
  return new Response(JSON.stringify(body), {
    headers: {
      "content-type": "application/json; charset=utf-8",
      // 브라우저·앱은 매번 새로 묻되, Netlify CDN이 3시간 동안 같은 응답을 준다. 오피넷이 잠깐 죽어도 하루는 지난 값을 준다.
      "cache-control": "public, max-age=0, must-revalidate",
      "netlify-cdn-cache-control": fuel
        ? "public, durable, s-maxage=10800, stale-while-revalidate=86400"
        : "public, durable, s-maxage=300",
    },
  });
};

export const config = { path: "/api/prices" };

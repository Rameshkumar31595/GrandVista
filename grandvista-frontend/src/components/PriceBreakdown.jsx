import { formatDate, formatMoney } from './common';

/** Shows how the Room Service dynamic price was calculated. */
export default function PriceBreakdown({ quote }) {
  if (!quote) return null;
  const adjusted = Number(quote.pricingAdjustment) !== 0;

  return (
    <div className="price-breakdown">
      <div className="price-row"><span>Base price per night</span><strong>{formatMoney(quote.basePricePerNight)}</strong></div>
      <div className="price-row"><span>{quote.nights} night(s) at base price</span><span>{formatMoney(quote.baseTotal)}</span></div>
      <div className="price-row"><span>Hotel occupancy for these dates</span><span>{quote.occupancyPercent}%</span></div>
      <div className="price-row"><span>Demand multiplier</span><span>× {Number(quote.demandMultiplier).toFixed(2)}</span></div>
      {quote.appliedRules.length > 0 && (
        <div className="price-row"><span>Applied pricing rules</span><span>{quote.appliedRules.join(', ')}</span></div>
      )}
      <div className={`price-row ${adjusted ? 'adjustment' : ''}`}>
        <span>Dynamic pricing adjustment</span><span>{formatMoney(quote.pricingAdjustment)}</span>
      </div>
      <div className="price-row total"><span>Total</span><strong>{formatMoney(quote.finalTotal)}</strong></div>

      <details className="nightly">
        <summary>Night-by-night calculation</summary>
        <table className="table compact">
          <thead><tr><th>Night</th><th>Seasonal ×</th><th>Demand ×</th><th>Price</th></tr></thead>
          <tbody>
            {quote.nightlyBreakdown.map((night) => (
              <tr key={night.date}>
                <td>{formatDate(night.date)}</td>
                <td>{Number(night.seasonalMultiplier).toFixed(2)}</td>
                <td>{Number(night.demandMultiplier).toFixed(2)}</td>
                <td>{formatMoney(night.price)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </div>
  );
}

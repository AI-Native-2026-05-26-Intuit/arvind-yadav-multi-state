import type { ReactElement } from 'react';
import { useParams } from 'react-router-dom';
import { useSummarizeTenantMutation } from '../gql/generated';

export function TenantSummaryPage(): ReactElement {
  const { id = '' } = useParams<{ id: string }>();
  const [summarize, { loading, data, error }] = useSummarizeTenantMutation({
    variables: { id },
    optimisticResponse: {
      summarizeTenant: {
        __typename: 'TenantSummary',
        id,
        summaryText: '...thinking...',
        confidence: 'MEDIUM',
      },
    },
  });

  return (
    <section>
      <h1>Tenant Summary</h1>
      <button type="button" onClick={() => { void summarize({ variables: { id } }); }} disabled={loading}>
        Summarize
      </button>
      {loading && <div role="status" aria-label="loading…">loading…</div>}
      {error && <div role="alert">Error: {error.message}</div>}
      {!loading && !error && data && (
        <article>
          <p>{data.summarizeTenant.summaryText}</p>
          <p>confidence: {data.summarizeTenant.confidence}</p>
        </article>
      )}
      {!loading && !error && !data && (
        <div role="status" aria-label="no results">no results</div>
      )}
    </section>
  );
}

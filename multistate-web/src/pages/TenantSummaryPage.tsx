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
      <button type="button" onClick={() => { void summarize({ variables: { id } }); }} disabled={loading}>
        Summarize
      </button>
      {error && <div role="alert">Error: {error.message}</div>}
      {data && (
        <article>
          <p>{data.summarizeTenant.summaryText}</p>
          <p>confidence: {data.summarizeTenant.confidence}</p>
        </article>
      )}
    </section>
  );
}

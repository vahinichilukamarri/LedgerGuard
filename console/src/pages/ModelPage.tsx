import { useModel } from '../api/queries';
import { Failure, Pending } from '../components/States';
import { instant } from '../components/uncertainty/format';

/**
 * Which model produced the second opinion.
 *
 * `ModelMetadata` travels on every scored response as well, because a forest
 * score cannot be recomputed from the ledger by reading a formula — the seed and
 * the training snapshot are the only things that make it reproducible. This page
 * exists so a reviewer can check that once rather than reading it off a row.
 *
 * A 404 is not an error here. No model trained is a state the system is designed
 * to be in, and the page says what that means for every other screen rather than
 * showing a failure.
 */
export function ModelPage() {
  const query = useModel();

  if (query.isPending) {
    return <Pending what="the model metadata" />;
  }
  if (query.isError) {
    return (
      <Failure what="the model metadata" error={query.error} onRetry={() => void query.refetch()} />
    );
  }

  const model = query.data;

  if (!model) {
    return (
      <section className="card">
        <h2>No model has been trained</h2>
        <p className="card-note">
          Every account in the console will show one layer only. That is an absence of a second
          opinion, not a second opinion that found nothing — an isolation score of zero would be a
          claim, and none is being made.
        </p>
        <p className="card-note">
          Training is explicit rather than lazy: a model that appeared as a side effect of the first
          read would be trained on whatever the ledger held at that moment, and nobody would know
          which snapshot they got. <code>POST /detection/model/train</code> trains one.
        </p>
      </section>
    );
  }

  return (
    <section className="card">
      <h2>Loaded isolation forest</h2>
      <p className="card-note">
        The scores this model produces are unvalidated. This page says which model produced them, so
        a figure can be traced back to a snapshot rather than to "the model".
      </p>
      <dl className="facts">
        <dt>Seed</dt>
        <dd>{model.seed}</dd>
        <dt>Trees</dt>
        <dd>{model.trees}</dd>
        <dt>Sub-sample size</dt>
        <dd>{model.subSampleSize}</dd>
        <dt>Training accounts</dt>
        <dd>{model.trainingAccounts}</dd>
        <dt>Trained at</dt>
        <dd>{instant(model.trainedAt)}</dd>
        <dt>Ledger as of</dt>
        <dd>{instant(model.trainedAsOf)}</dd>
      </dl>
    </section>
  );
}

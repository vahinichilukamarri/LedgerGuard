import type { CSSProperties } from 'react';
import { useModel, useTrainModel } from '../api/queries';
import { Failure, Pending, describe } from '../components/States';
import { Icon } from '../components/icons/Icon';
import { CountUp } from '../components/motion/CountUp';
import { Reveal } from '../components/motion/Reveal';
import { PageHeader } from '../components/PageHeader';
import { instant } from '../components/uncertainty/format';

/** The backend refuses to train below this many accounts (see `insufficient_training_data`). */
const MINIMUM_TRAINING_ACCOUNTS = 32;

/** `amountModifiedZ` → "Amount modified z", `log10SecondsSinceLastPayment` → "Log10 seconds since last payment". */
function readable(feature: string): string {
  const spaced = feature.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/**
 * Which model produced the second opinion.
 *
 * `ModelMetadata` travels on every scored response as well, because a forest
 * score cannot be recomputed from the ledger by reading a formula — the seed and
 * the training snapshot are the only things that make it reproducible. This page
 * exists so a reviewer can check that once rather than reading it off a row, and
 * to train (or retrain) explicitly: training is never a side effect of a read.
 *
 * A 404 is not an error here. No model trained is a state the system is designed
 * to be in, and the page says what that means for every other screen rather than
 * showing a failure.
 */
export function ModelPage() {
  const query = useModel();
  const train = useTrainModel();

  if (query.isPending) {
    return <Pending what="the model metadata" />;
  }
  if (query.isError) {
    return (
      <Failure what="the model metadata" error={query.error} onRetry={() => void query.refetch()} />
    );
  }

  const model = query.data;

  const trainButton = (
    <button type="button" className="btn btn-primary" disabled={train.isPending} onClick={() => train.mutate()}>
      <Icon name={train.isPending ? 'refresh' : 'bolt'} className={train.isPending ? 'spin' : undefined} />{' '}
      {train.isPending ? 'Training…' : model ? 'Retrain model' : 'Train a model'}
    </button>
  );

  const header = (
    <PageHeader
      icon="model"
      title="Model"
      description="Which isolation forest produced the second opinion, and on what snapshot."
      action={trainButton}
    />
  );

  const failure = train.isError ? (
    <p className="form-error" role="alert">
      {describe(train.error)}
    </p>
  ) : null;

  if (!model) {
    return (
      <>
        {header}
        {failure}
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
            which snapshot they got. <code>POST /detection/model/train</code> trains one, and needs at
            least {MINIMUM_TRAINING_ACCOUNTS} accounts to have anything to isolate.
          </p>
        </section>
      </>
    );
  }

  const facts: { label: string; value: number; note: string }[] = [
    { label: 'Trees', value: model.trees, note: 'in the forest' },
    { label: 'Sub-sample size', value: model.subSampleSize, note: 'accounts drawn per tree' },
    { label: 'Training accounts', value: model.trainingAccounts, note: `minimum ${MINIMUM_TRAINING_ACCOUNTS}` },
  ];
  const features = model.featureNames ?? [];

  return (
    <div className="model-page">
      {header}
      {failure}

      <Reveal>
        <section className="model-hero">
          <div className="model-hero-main">
            <span className="model-hero-badge">
              <span className="hero-pill-dot" /> Loaded isolation forest
            </span>
            <h2>
              Seed <span className="num">{model.seed}</span>
            </h2>
            <p>
              The scores this model produces are unvalidated. This page says which model produced them, so a
              figure can be traced back to a snapshot rather than to “the model”.
            </p>
            <dl className="model-times">
              <div>
                <dt>Trained at</dt>
                <dd>{instant(model.trainedAt)}</dd>
              </div>
              <div>
                <dt>Ledger as of</dt>
                <dd>{instant(model.trainedAsOf)}</dd>
              </div>
            </dl>
          </div>
          <div className="model-facts">
            {facts.map((fact, i) => (
              <div key={fact.label} className="model-fact" style={{ '--i': i } as CSSProperties}>
                <span className="model-fact-value">
                  <CountUp value={fact.value} />
                </span>
                <span className="model-fact-label">{fact.label}</span>
                <span className="model-fact-note">{fact.note}</span>
              </div>
            ))}
          </div>
        </section>
      </Reveal>

      <Reveal delay={80}>
        <section className="card">
          <h2>Training population</h2>
          <p className="card-note">
            How far this model’s training set is above the {MINIMUM_TRAINING_ACCOUNTS}-account floor below which a
            forest only describes the size of the dataset.
          </p>
          <div
            className="meter"
            role="meter"
            aria-valuemin={0}
            aria-valuenow={model.trainingAccounts}
            aria-valuemax={Math.max(model.trainingAccounts, MINIMUM_TRAINING_ACCOUNTS * 2)}
            aria-label="Training accounts against the minimum"
          >
            <span
              className="meter-fill"
              style={{
                width: `${Math.min(100, (model.trainingAccounts / Math.max(model.trainingAccounts, MINIMUM_TRAINING_ACCOUNTS * 2)) * 100)}%`,
              }}
            />
            <span
              className="meter-mark"
              style={{ left: `${(MINIMUM_TRAINING_ACCOUNTS / Math.max(model.trainingAccounts, MINIMUM_TRAINING_ACCOUNTS * 2)) * 100}%` }}
            >
              <em>minimum {MINIMUM_TRAINING_ACCOUNTS}</em>
            </span>
          </div>
        </section>
      </Reveal>

      {features.length > 0 && (
        <Reveal delay={120}>
          <section className="card">
            <h2>What the model looks at</h2>
            <p className="card-note">
              The {features.length} features every account is described by. Which of them drove a particular
              account’s score is on that account’s page, under model attribution.
            </p>
            <ul className="feature-chips">
              {features.map((feature, i) => (
                <li key={feature} style={{ '--i': i } as CSSProperties}>
                  <span className="feature-chip-index">{i + 1}</span>
                  {readable(feature)}
                </li>
              ))}
            </ul>
          </section>
        </Reveal>
      )}
    </div>
  );
}

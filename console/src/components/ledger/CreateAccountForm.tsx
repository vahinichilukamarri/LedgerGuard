import { useState, type FormEvent } from 'react';
import { useCreateAccount } from '../../api/queries';
import { describe } from '../States';

/** POST /accounts. `CreateAccountRequest` requires a name and a 3-letter ISO-4217 currency code. */
export function CreateAccountForm() {
  const [name, setName] = useState('');
  const [currency, setCurrency] = useState('USD');
  const mutation = useCreateAccount();

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    mutation.mutate(
      { name, currency },
      {
        onSuccess: () => {
          setName('');
        },
      },
    );
  }

  return (
    <form className="form" onSubmit={onSubmit}>
      <div className="form-row">
        <label className="field">
          <span className="field-label">Name</span>
          <input
            type="text"
            value={name}
            onChange={(event) => setName(event.target.value)}
            required
            maxLength={200}
          />
        </label>
        <label className="field">
          <span className="field-label">Currency</span>
          <input
            type="text"
            value={currency}
            onChange={(event) => setCurrency(event.target.value.toUpperCase())}
            required
            maxLength={3}
            minLength={3}
            pattern="[A-Za-z]{3}"
            className="num"
            style={{ width: 90 }}
          />
        </label>
      </div>
      <div className="form-actions">
        <button type="submit" className="btn btn-primary" disabled={mutation.isPending}>
          {mutation.isPending ? 'Creating…' : 'Create account'}
        </button>
        {mutation.isSuccess && <span className="form-success">Created {mutation.data.name}.</span>}
      </div>
      {mutation.isError && <p className="form-error">{describe(mutation.error)}</p>}
    </form>
  );
}

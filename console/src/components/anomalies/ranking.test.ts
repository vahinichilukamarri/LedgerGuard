import { describe, expect, it } from 'vitest';
import { assessment } from '../../test/fixtures';
import { filterRows, pageCount, pageOf, sortRows, stateOf } from './ranking';
import type { AccountAssessment } from '../../api/types';

const rows: AccountAssessment[] = [
  { ...assessment('a1', 'BOTH_ELEVATED'), statisticalScore: 0.9 },
  { ...assessment('a2', 'ML_ONLY'), statisticalScore: 0.3 },
  { ...assessment('a3', 'STATISTICAL_ONLY'), statisticalScore: 0.6 },
  { ...assessment('a4', null), statisticalScore: 0.55 },
];

describe('ranking', () => {
  it('keeps the API order under the rank sort rather than reordering it', () => {
    expect(sortRows(rows, 'rank', 'desc').map((row) => row.accountId)).toEqual([
      'a1',
      'a2',
      'a3',
      'a4',
    ]);
  });

  it('sorts an account with no model score below every account that has one', () => {
    // Not alongside the accounts the model scored zero: no score and a score of
    // zero are different statements, and only one of them is a measurement.
    const sorted = sortRows(rows, 'ml', 'desc');
    expect(sorted[sorted.length - 1]?.accountId).toBe('a4');
  });

  it('breaks ties by the API ranking position, so two renders agree exactly', () => {
    const tied = [
      { ...assessment('t1', 'BOTH_ELEVATED'), statisticalScore: 0.5 },
      { ...assessment('t2', 'BOTH_ELEVATED'), statisticalScore: 0.5 },
      { ...assessment('t3', 'BOTH_ELEVATED'), statisticalScore: 0.5 },
    ];
    const once = sortRows(tied, 'statistical', 'desc').map((row) => row.accountId);
    const twice = sortRows(tied, 'statistical', 'desc').map((row) => row.accountId);
    expect(once).toEqual(['t1', 't2', 't3']);
    expect(once).toEqual(twice);
  });

  it('derives the same display state the chip will render', () => {
    expect(stateOf(rows[0] as AccountAssessment)).toBe('BOTH_ELEVATED_SHARED_AXIS');
    expect(stateOf(rows[3] as AccountAssessment)).toBe('NO_MODEL');
  });

  it('filters by display state, which is finer than the enum', () => {
    const unrelated: AccountAssessment = {
      ...assessment('a5', 'BOTH_ELEVATED'),
      explanation: { ...assessment('a5', 'BOTH_ELEVATED').explanation, corroborated: false },
    };
    const all = [...rows, unrelated];

    expect(
      filterRows(all, '', ['BOTH_ELEVATED_UNRELATED']).map((row) => row.accountId),
    ).toEqual(['a5']);
    expect(filterRows(all, '', ['BOTH_ELEVATED_SHARED_AXIS']).map((row) => row.accountId)).toEqual([
      'a1',
    ]);
  });

  it('treats no selected state as no filter', () => {
    expect(filterRows(rows, '', [])).toHaveLength(4);
  });

  it('matches an account id substring case-insensitively', () => {
    expect(filterRows(rows, 'A3', []).map((row) => row.accountId)).toEqual(['a3']);
  });

  it('pages without dropping or duplicating a row', () => {
    const many = Array.from({ length: 57 }, (_, index) => assessment(`x${index}`, 'BOTH_QUIET'));
    expect(pageCount(many.length, 25)).toBe(3);

    const seen = [
      ...pageOf(many, 0, 25),
      ...pageOf(many, 1, 25),
      ...pageOf(many, 2, 25),
    ].map((row) => row.accountId);

    expect(seen).toHaveLength(57);
    expect(new Set(seen).size).toBe(57);
  });

  it('reports at least one page for an empty ranking', () => {
    expect(pageCount(0)).toBe(1);
  });
});

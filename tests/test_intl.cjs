function assert(condition, message) {
  if (!condition) throw new Error(message);
}

assert(typeof Intl === 'object', 'expected Intl global');
assert(Intl.constructor === Object, 'expected Intl to inherit from Object');

const collator = Intl.Collator('de-DE');
assert(collator instanceof Intl.Collator, 'expected Intl.Collator() to create an instance');
assert(typeof collator.compare === 'function', 'expected collator.compare');
assert(typeof collator.resolvedOptions === 'function', 'expected collator.resolvedOptions');

const defaultLocale = Intl.Collator().resolvedOptions().locale;
assert(Intl.Collator(0, { numeric: 1 }).resolvedOptions().locale === defaultLocale, 'expected numeric locale argument to default');
assert(Intl.Collator(true).resolvedOptions().locale === defaultLocale, 'expected boolean locale argument to default');
assert(Intl.Collator({}).resolvedOptions().locale === defaultLocale, 'expected empty locale-list object to default');
assert(Intl.Collator({ 0: 'de-DE', length: 1 }).resolvedOptions().locale === 'de-DE', 'expected object locale list entry');

const numericCompare = new Intl.Collator(0, { numeric: 1 }).compare;
assert(numericCompare('2', '10') < 0, 'expected extracted numeric collator compare to sort numbers numerically');
assert(numericCompare('a2', 'a10') < 0, 'expected extracted numeric collator compare to sort embedded numbers numerically');

const numberFormat = new Intl.NumberFormat('en-US');
assert(numberFormat instanceof Intl.NumberFormat, 'expected NumberFormat instance');
assert(numberFormat.format(1234567.5) === '1,234,567.5', `unexpected formatted number: ${numberFormat.format(1234567.5)}`);

const dateTimeFormat = Intl.DateTimeFormat('en-US', { timeZone: 'Australia/Sydney' });
assert(dateTimeFormat instanceof Intl.DateTimeFormat, 'expected DateTimeFormat() to create an instance');
const dtfOptions = dateTimeFormat.resolvedOptions();
assert(dtfOptions.timeZone === 'Australia/Sydney', `unexpected timeZone: ${dtfOptions.timeZone}`);
const dtfFormatted = dateTimeFormat.format(0);
const dtfParts = dateTimeFormat.formatToParts(0);
assert(Array.isArray(dtfParts), 'expected formatToParts() to return an array');
assert(dtfParts.length === 7, `unexpected formatToParts() length: ${dtfParts.length}`);
assert(dtfParts.map(part => part.value).join('') === dtfFormatted, 'expected formatToParts() values to match format() output');
assert(dtfParts[0].type === 'hour', `unexpected first part type: ${dtfParts[0].type}`);
assert(dtfParts[6].type === 'dayPeriod', `unexpected last part type: ${dtfParts[6].type}`);

let rejected = false;
try {
  Intl.Collator('x-en-US-12345');
} catch (error) {
  rejected = true;
}
assert(rejected, 'expected invalid language tags to throw');

rejected = false;
try {
  Intl.Collator([0]);
} catch (error) {
  rejected = error instanceof TypeError;
}
assert(rejected, 'expected non-string locale list entries to throw TypeError');

const segmenter = Intl.Segmenter('en-US', { granularity: 'word' });
const segments = segmenter.segment('ok');
assert(Array.isArray(segments), 'expected Intl.Segmenter().segment() to return an array');
assert(segments.length === 2, `unexpected segment count: ${segments.length}`);

console.log('intl test passed');

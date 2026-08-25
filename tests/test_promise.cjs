console.log('Test 1: Promise Constructor');
let p = new Promise((resolve, reject) => {
  resolve(42);
});

console.log('p is Promise: ' + (p instanceof Promise));

p.then(v => {
  console.log('Resolved with ' + v);
});

console.log('Test 2: Chaining');
let p2 = new Promise(resolve => resolve(10));
p2.then(v => {
  return v * 2;
}).then(v => {
  console.log('Chained result: ' + v); // Should be 20
});

console.log('Test 3: Catch');
let p3 = new Promise((_, reject) => reject('error'));
p3.catch(e => {
  console.log('Caught: ' + e);
});

console.log('Test 4: Static resolve');
Promise.resolve('static').then(v => {
  console.log('Static resolve: ' + v);
});

console.log('Test 5: Promise.try');
Promise.try(() => {
  return 'try';
}).then(v => {
  console.log('Try result: ' + v);
});

console.log('Test 6: Finally');
Promise.resolve('fin').finally(() => {
  console.log('Finally called');
});

console.log('Test 7: Reentrant handler append');
let resolveReentrant;
const reentrant = new Promise(resolve => {
  resolveReentrant = resolve;
});
const reentrantChildren = [];
for (let i = 0; i < 8; i++) {
  reentrantChildren.push(reentrant.then(v => {
    if (i === 0) {
      for (let j = 0; j < 2048; j++) reentrant.then(() => j);
    }
    return v + i;
  }));
}
resolveReentrant(10);
Promise.all(reentrantChildren).then(values => {
  console.log('Reentrant handler result: ' + values.join(','));
});

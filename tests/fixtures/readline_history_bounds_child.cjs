const readline = require('node:readline');

const longHistoryLine = 'x'.repeat(9000);
const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
  history: [longHistoryLine],
});

rl.question('Q: ', answer => {
  console.log(`ANSWER_LEN ${answer.length}`);
  console.log(`ANSWER_OK ${answer === 'x'.repeat(4095)}`);
  rl.close();
});

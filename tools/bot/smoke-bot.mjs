// Joins the test server as a player, runs /nanocore version and waits for the plugin's answer.
// Usage: node smoke-bot.mjs [host] [port]. Exit code 0 = pass.
import mineflayer from 'mineflayer'

const host = process.argv[2] ?? '127.0.0.1'
const port = Number(process.argv[3] ?? 25599)
const bot = mineflayer.createBot({ host, port, username: 'NanoBot', version: '26.1', auth: 'offline' })

const finish = (code, msg) => {
  console.log(msg)
  bot.end()
  setTimeout(() => process.exit(code), 500)
}
const timer = setTimeout(() => finish(1, 'BOT FAIL: no answer within 60 s'), 60_000)

bot.once('spawn', () => {
  console.log('BOT: spawned, sending /nanocore version')
  setTimeout(() => bot.chat('/nanocore version'), 1500)
})
bot.on('messagestr', (text) => {
  console.log('BOT chat:', text)
  if (/NanoCore .* on /.test(text)) {
    clearTimeout(timer)
    finish(0, 'BOT PASS: player command answered')
  }
})
bot.on('kicked', (reason) => finish(1, 'BOT FAIL: kicked: ' + JSON.stringify(reason)))
bot.on('error', (err) => finish(1, 'BOT FAIL: ' + err.message))

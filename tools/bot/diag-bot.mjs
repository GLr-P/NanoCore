// Diagnostics: joins with physics on, stays 8 s, walks a little, reports any kick. Exit 0 = stayed connected.
import { createTestBot } from './lib.mjs'

const bot = createTestBot('DiagBot')
const t0 = Date.now()
let kicked = false
bot.once('spawn', () => {
  bot.setControlState('forward', true)
  setTimeout(() => bot.setControlState('forward', false), 1500)
})
bot.on('kicked', (r) => {
  kicked = true
  console.log(`DIAG +${Date.now() - t0}ms kicked`, JSON.stringify(r))
})
bot.on('end', () => process.exit(kicked ? 1 : 0))
setTimeout(() => {
  console.log(`DIAG stayed connected 8 s, moved to ${bot.entity?.position}`)
  bot.end()
}, 8000)

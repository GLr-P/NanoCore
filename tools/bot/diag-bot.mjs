// Diagnostics only: joins, logs every packet it sends (first 4 of each kind) for 6 s, reports a kick.
import mineflayer from 'mineflayer'

const physics = process.env.BOT_PHYSICS !== '0'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25599, username: 'DiagBot', version: '26.1', auth: 'offline', physicsEnabled: physics })
const seen = {}
const t0 = Date.now()
const write = bot._client.write.bind(bot._client)
bot._client.write = (name, params) => {
  seen[name] = (seen[name] ?? 0) + 1
  if (seen[name] <= 4 && name !== 'keep_alive') {
    console.log(`DIAG +${Date.now() - t0}ms sends ${name}`, JSON.stringify(params, (_k, v) => (typeof v === 'bigint' ? v.toString() : v)).slice(0, 300))
  }
  return write(name, params)
}
bot._client.on('packet', (data, meta) => {
  if (/position|teleport|disconnect|kick/.test(meta.name)) console.log(`DIAG +${Date.now() - t0}ms gets ${meta.name}`, JSON.stringify(data).slice(0, 300))
})
bot.on('kicked', (r) => console.log(`DIAG +${Date.now() - t0}ms kicked (physics=${physics})`, JSON.stringify(r)))
bot.on('end', () => {
  console.log('DIAG packet counts', JSON.stringify(seen))
  process.exit(0)
})
setTimeout(() => bot.end(), 6000)

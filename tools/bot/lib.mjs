// Shared helpers for test bots.
import mineflayer from 'mineflayer'

// Mineflayer 4.39 fills a "time" field in movement packets for 26.1. Through ViaBackwards to a 26.3 server
// any non-zero value gets the bot kicked for "invalid_player_movement" (seen 2026-11-23), so it is forced to 0.
const MOVE_PACKETS = new Set(['position', 'position_look', 'look', 'flying'])

export function createTestBot(username, opts = {}) {
  const bot = mineflayer.createBot({
    host: opts.host ?? '127.0.0.1',
    port: opts.port ?? 25599,
    username,
    version: '26.1',
    auth: 'offline',
    ...opts,
  })
  const write = bot._client.write.bind(bot._client)
  bot._client.write = (name, params) => {
    if (MOVE_PACKETS.has(name) && params && 'time' in params) params = { ...params, time: 0 }
    if (process.env.DEBUG_PACKETS === '1' && name !== 'keep_alive') {
      console.log('BOT sends', name, JSON.stringify(params, (_k, v) => (typeof v === 'bigint' ? v.toString() : v)).slice(0, 250))
    }
    return write(name, params)
  }
  // Real clients send tick_end every tick (since 1.21.2); Mineflayer 4.39 doesn't. Without it the server
  // kicks the bot for "invalid_player_movement" about a second after spawning.
  let ticker
  bot.once('login', () => {
    ticker = setInterval(() => {
      if (bot._client.state === 'play') write('tick_end', {})
    }, 50)
  })
  bot.on('end', () => clearInterval(ticker))
  return bot
}

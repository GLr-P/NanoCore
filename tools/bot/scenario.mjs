// Play-tests NanoCore as a real player. Usage: node scenario.mjs <first|second>
// "first" runs on a fresh server; "second" runs after a restart and checks that everything was saved.
// Prints "SCENARIO PASS <phase>" or "SCENARIO FAIL <phase>: <step>" and exits 0/1.
import { createTestBot } from './lib.mjs'
import { Vec3 } from 'vec3'

const phase = process.argv[2] ?? 'first'
const bot = createTestBot('NanoBot')
const chat = []
bot.on('messagestr', (t) => {
  chat.push(t)
  console.log('  chat:', t)
})

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** Sends a command and waits for a chat line matching the pattern. */
async function expect(command, pattern, step, timeout = 8000) {
  const start = chat.length
  console.log(`STEP ${step}: ${command}`)
  bot.chat(command)
  const until = Date.now() + timeout
  while (Date.now() < until) {
    const hit = chat.slice(start).find((t) => pattern.test(t))
    if (hit) return hit
    await sleep(100)
  }
  throw new Error(`${step} (no chat line matching ${pattern})`)
}

async function digBelow(step) {
  const target = bot.blockAt(bot.entity.position.offset(0, -1, 0))
  if (!target || target.name === 'air') throw new Error(`${step} (nothing to dig below)`)
  console.log(`STEP ${step}: dig ${target.name} at ${target.position}`)
  await bot.dig(target, true)
  return { name: target.name, at: target.position }
}

async function first() {
  await expect('/nanocore version', /NanoCore .* on /, 'version')
  await expect('/balance', /Balance: 100 coins/, 'starting money')
  await expect('/nanocore eco give NanoBot 50', /now has 150 coins/, 'admin give')
  await expect('/pay NanoBot 5', /can't pay yourself/, 'no self pay')
  await expect('/pay NanoBot 999', /can't pay yourself|don't have enough/, 'pay checks')
  await expect('/baltop', /1\. NanoBot 150 coins/, 'baltop')

  // Natural block: counts. Placed block: doesn't.
  const { name: dug, at: dugAt } = await digBelow('dig natural')
  await sleep(500)
  await expect(`/nanocore stats NanoBot mine.`, new RegExp(`mine\\.${dug}=1`), 'natural block counted')
  await expect('/give NanoBot dirt 1', /Gave 1/, 'give dirt')
  await sleep(500)
  const dirt = bot.inventory.items().find((i) => i.name === 'dirt')
  if (!dirt) throw new Error('give dirt (no dirt in inventory)')
  await bot.equip(dirt, 'hand')
  // The ground block next to the hole we just dug; the dirt goes on top of it.
  const floor = bot.blockAt(dugAt.offset(1, 0, 0))
  console.log(`STEP place dirt on ${floor?.name} at ${floor?.position}`)
  await bot.placeBlock(floor, new Vec3(0, 1, 0))
  await sleep(500)
  const placed = bot.blockAt(floor.position.offset(0, 1, 0))
  if (placed?.name !== 'dirt') throw new Error(`place dirt (found ${placed?.name})`)
  await bot.dig(placed, true)
  await sleep(500)
  const line = await expect('/nanocore stats NanoBot', /Stats for NanoBot/, 'placed not counted')
  if (/mine\.dirt=/.test(line)) throw new Error('placed not counted (placed dirt was counted as mined)')
  if (!/place\.dirt=1/.test(line)) throw new Error('placed not counted (place.dirt missing)')

  // Milestones
  await expect('/nanocore milestone list', /nether: locked/, 'milestones start locked')
  await expect('/nanocore milestone unlock nether', /The Nether.*now unlocked/, 'unlock nether')
  await expect('/nanocore milestone unlock nether', /already unlocked/, 'unlock twice')
  const opened = new Promise((resolve) => bot.once('windowOpen', (w) => resolve(w)))
  bot.chat('/milestones')
  const win = await Promise.race([opened, sleep(5000).then(() => null)])
  if (!win) throw new Error('milestones menu (no window opened)')
  console.log(`STEP milestones menu opened: ${JSON.stringify(win.title).slice(0, 80)}, ${win.slots.filter(Boolean).length} items`)
  bot.closeWindow(win)

  // Areas
  await expect('/nanocore area circle town 50', /Area town saved/, 'area circle')
  await expect('/nanocore area list', /town: .*radius 50/, 'area list')
}

async function second() {
  await expect('/balance', /Balance: 150 coins/, 'balance saved')
  await expect('/nanocore milestone list', /nether: unlocked/, 'milestone saved')
  await expect('/nanocore stats NanoBot place.', /place\.dirt=1/, 'stats saved')
  await expect('/nanocore area list', /town: .*radius 50/, 'area saved')
}

bot.once('spawn', async () => {
  await sleep(1500)
  try {
    await (phase === 'first' ? first() : second())
    console.log(`SCENARIO PASS ${phase}`)
    bot.end()
    setTimeout(() => process.exit(0), 300)
  } catch (err) {
    console.log(`SCENARIO FAIL ${phase}: ${err.message}`)
    bot.end()
    setTimeout(() => process.exit(1), 300)
  }
})
bot.on('kicked', (r) => {
  console.log(`SCENARIO FAIL ${phase}: kicked ${JSON.stringify(r)}`)
  process.exit(1)
})
bot.on('error', (e) => {
  console.log(`SCENARIO FAIL ${phase}: ${e.message}`)
  process.exit(1)
})
setTimeout(() => {
  console.log(`SCENARIO FAIL ${phase}: timed out`)
  process.exit(1)
}, 120_000)

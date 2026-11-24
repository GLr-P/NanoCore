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

  // Nether portal: won't light while locked, lights once unlocked.
  const p = bot.entity.position.floored()
  const fx = p.x + 3
  await expect(`/fill ${fx} ${p.y} ${p.z} ${fx} ${p.y + 4} ${p.z + 3} obsidian`, /Successfully filled|blocks? filled/i, 'portal frame')
  await expect(`/fill ${fx} ${p.y + 1} ${p.z + 1} ${fx} ${p.y + 3} ${p.z + 2} air`, /Successfully filled|blocks? filled/i, 'portal hole')
  await expect('/give NanoBot flint_and_steel 1', /Gave 1/, 'give flint')
  await sleep(500)
  const lightPortal = async (step) => {
    const flint = bot.inventory.items().find((i) => i.name === 'flint_and_steel')
    if (!flint) throw new Error(`${step} (no flint and steel)`)
    await bot.equip(flint, 'hand')
    const base = bot.blockAt(new Vec3(fx, p.y, p.z + 1))
    console.log(`STEP ${step}: light ${base?.name} at ${base?.position}`)
    await bot.lookAt(base.position.offset(0.5, 1, 0.5), true)
    await bot.activateBlock(base, new Vec3(0, 1, 0))
    await sleep(1500)
    return bot.blockAt(new Vec3(fx, p.y + 1, p.z + 1))?.name
  }
  const locked = await lightPortal('light locked portal')
  if (locked === 'nether_portal') throw new Error('portal lock (portal formed while the Nether is locked)')
  await expect(`/setblock ${fx} ${p.y + 1} ${p.z + 1} air`, /Changed the block|block/i, 'clear fire')

  // Nether roof: a player above the roof is sent back down.
  // Enter the Nether first (on a fresh glass platform), let the bot settle, then go up to the roof.
  await expect('/execute in minecraft:the_nether run fill 7 99 7 9 99 9 glass', /Successfully filled|blocks? filled/i, 'nether platform')
  await expect('/execute in minecraft:the_nether run fill 7 100 7 9 102 9 air', /Successfully filled|blocks? filled|No blocks/i, 'nether platform air')
  await expect('/execute in minecraft:the_nether run tp NanoBot 8.5 100 8.5', /Teleported/, 'tp into nether')
  await sleep(2000)
  await bot.waitForChunksToLoad()
  await sleep(2000)
  await expect('/tp NanoBot 8.5 129 8.5', /Teleported/, 'tp to nether roof')
  await sleep(2000)
  bot.setControlState('forward', true)
  await sleep(1500)
  bot.setControlState('forward', false)
  await sleep(1500)
  console.log(`STEP roof: bot at ${bot.entity.position}`)
  if (bot.entity.position.y > 127) throw new Error(`nether roof (still above the roof at y ${bot.entity.position.y})`)
  if (!chat.some((t) => /Nether roof is off limits/.test(t))) throw new Error('nether roof (no message)')
  await expect(`/execute in minecraft:overworld run tp NanoBot ${p.x + 0.5} ${p.y} ${p.z + 0.5}`, /Teleported/, 'tp back')
  await sleep(2000)

  await expect('/nanocore milestone unlock nether', /The Nether.*now unlocked/, 'unlock nether')
  const unlocked = await lightPortal('light unlocked portal')
  if (unlocked !== 'nether_portal') throw new Error(`portal unlock (found ${unlocked} instead of a portal)`)
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

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

/** Runs an action and waits for a chat line matching the pattern. */
async function expectAfter(action, pattern, step, timeout = 8000) {
  const start = chat.length
  console.log(`STEP ${step}`)
  await action()
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
  await expect('/execute in minecraft:the_nether run forceload add 0 0 15 15', /[Cc]hunk|force/i, 'load nether chunk', 30000)
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

  // Admin shop: 30 diamonds hit the 150 limit at 25; dirt isn't bought; elytra waits for its milestone.
  await expect('/give NanoBot diamond 30', /Gave 30/, 'give diamonds')
  await expect('/give NanoBot dirt 3', /Gave 3/, 'give dirt for shop')
  await expect('/give NanoBot elytra 1', /Gave 1/, 'give elytra')
  await sleep(500)
  const count = (name) => bot.inventory.items().filter((i) => i.name === name).reduce((n, i) => n + i.count, 0)
  const dirtBefore = count('dirt') // the earlier digging left some dirt too
  const shopOpened = new Promise((resolve) => bot.once('windowOpen', (w) => resolve(w)))
  bot.chat('/sell')
  const shop = await Promise.race([shopOpened, sleep(5000).then(() => null)])
  if (!shop) throw new Error('sell menu (no window opened)')
  console.log(`STEP sell menu opened: ${JSON.stringify(shop.title).slice(0, 80)}, inventory from ${shop.inventoryStart}`)
  await sleep(500)
  const slotOf = (name) => shop.slots.findIndex((it, i) => i >= shop.inventoryStart && it?.name === name)
  for (const [name, dest] of [['diamond', 0], ['elytra', 1]]) {
    const from = slotOf(name)
    if (from < 0) throw new Error(`sell menu (no ${name} in the bot's inventory)`)
    console.log(`STEP sell: move ${name} from slot ${from} to ${dest}`)
    await bot.moveSlotItem(from, dest)
    await sleep(400)
  }
  const dirtSlot = slotOf('dirt')
  console.log(`STEP sell: shift-click dirt from slot ${dirtSlot}`)
  await bot.clickWindow(dirtSlot, 0, 1)
  await sleep(800)
  const top = shop.slots.slice(0, 36).filter(Boolean).map((it) => `${it.name}x${it.count}`)
  console.log(`STEP sell: drop area holds ${top.join(', ')}`)
  if (!top.some((t) => t.startsWith('dirt'))) throw new Error('sell shift-click (dirt did not move into the drop area)')
  if (shop.slots.slice(36, 45).some((it) => it?.name === 'dirt')) throw new Error('sell shift-click (dirt landed in the button row)')
  const closed = new Promise((resolve) => bot.once('windowClose', () => resolve(true)))
  await expectAfter(() => bot.clickWindow(44, 0, 0), /Sold 25 items for 150 coins/, 'sell confirm')
  await Promise.race([closed, sleep(3000)])
  await sleep(1000)
  console.log(`STEP sell: back in inventory diamond=${count('diamond')} dirt=${count('dirt')} elytra=${count('elytra')}`)
  if (count('diamond') !== 5) throw new Error(`sell leftovers (expected 5 diamonds back, have ${count('diamond')})`)
  if (count('dirt') !== dirtBefore) throw new Error(`sell leftovers (expected ${dirtBefore} dirt back, have ${count('dirt')})`)
  if (count('elytra') !== 1) throw new Error('sell leftovers (elytra not returned)')
  await expect('/balance', /Balance: 300 coins/, 'paid by the shop')
  await expect('/sell', /sold all you can today/, 'daily limit')
  await expect('/nanocore shop status NanoBot', /sold 150 coins of 150 coins today/, 'shop status')

  await quests(count)
}

// --- Quests: a board block and storehouse chest on the flat world (grass at y -61, standing at -60) ---
const BOARD = new Vec3(42, -60, 45)

async function waitChat(start, pattern, step, timeout = 8000) {
  const until = Date.now() + timeout
  while (Date.now() < until) {
    const hit = chat.slice(start).find((t) => pattern.test(t))
    if (hit) return hit
    await sleep(100)
  }
  throw new Error(`${step} (no chat line matching ${pattern})`)
}

async function windowAfter(action, step) {
  const opened = new Promise((resolve) => bot.once('windowOpen', (w) => resolve(w)))
  await action()
  const w = await Promise.race([opened, sleep(5000).then(() => null)])
  if (!w) throw new Error(`${step} (no window opened)`)
  await sleep(300)
  return w
}

const openBoard = (step) => windowAfter(() => bot.activateBlock(bot.blockAt(BOARD)), step)
const click = (slot, step) => {
  console.log(`STEP ${step}: click slot ${slot}`)
  return windowAfter(() => bot.clickWindow(slot, 0, 0), step)
}

/** Opens quest n's page from the board (quests 1-5 are slots 10-14), clicks a button, waits for a chat line. */
async function questButton(slot, button, pattern, step) {
  await openBoard(`${step}: open board`)
  await click(slot, `${step}: quest page`)
  const start = chat.length
  const w = await click(button, step)
  await waitChat(start, pattern, step)
  bot.closeWindow(w)
  await sleep(300)
}

async function labelThere(step) {
  await sleep(6000) // labels are placed every 5 s
  await expect(`/execute positioned 42.5 -58.2 45.5 if entity @e[type=minecraft:text_display,distance=..1]`, /Test passed/, step)
}

async function quests(count) {
  await expect('/tp NanoBot 42.5 -60 42.5', /Teleported/, 'tp to quest area')
  await sleep(1500)
  await bot.waitForChunksToLoad()
  const ground = bot.blockAt(new Vec3(42, -61, 42))?.name
  if (ground !== 'grass_block') throw new Error(`quest area (expected grass at y -61, found ${ground})`)
  await expect(`/setblock ${BOARD.x} ${BOARD.y} ${BOARD.z} bookshelf`, /Changed the block/i, 'board block')
  await expect('/setblock 44 -60 45 chest', /Changed the block/i, 'storehouse chest')
  await expect(`/quests board set ${BOARD.x} ${BOARD.y} ${BOARD.z}`, /Quest board set at 42, -60, 45/, 'board set')
  await expect('/quests storehouse set 44 -60 45', /storehouse set at 44, -60, 45/, 'storehouse set')
  await expect('/quests list', /#11 .*\(hard, /, 'board has 11 daily quests')
  await expect('/quests set 1 dig_dirt 3', /#1 Dig dirt, sand or gravel x3 \(easy, dig_dirt\) 40 coins/, 'set quest 1')
  await expect('/quests set 2 deliver_cobblestone 10', /#2 Deliver cobblestone x10 \(easy, deliver_cobblestone\) 60 coins/, 'set quest 2')
  await expect('/quests set 12 dig_dirt 3', /#12 Dig dirt, sand or gravel x3 \(community, dig_dirt\) 1,500 coins/, 'set community quest')
  await labelThere('board label')

  const board = await openBoard('open board')
  console.log(`STEP board: ${JSON.stringify(board.title).slice(0, 60)}, slot 10 ${board.slots[10]?.name}, 11 ${board.slots[11]?.name}, 40 ${board.slots[40]?.name}`)
  if (board.slots[10]?.name !== 'dirt' || board.slots[11]?.name !== 'cobblestone') throw new Error('board layout (quests 1 and 2 not in slots 10 and 11)')
  bot.closeWindow(board)
  await sleep(300)

  await questButton(10, 11, /Quest taken: Dig dirt/, 'take quest 1')
  await questButton(11, 11, /Quest taken: Deliver cobblestone/, 'take quest 2')
  await questButton(12, 15, /Swapped .* for a new quest \(25 coins\)/, 'reroll quest 3')

  // Three natural blocks finish quest 1 and the community quest.
  const start = chat.length
  for (const [x, z] of [[43, 42], [41, 42], [42, 41]]) {
    const b = bot.blockAt(new Vec3(x, -61, z))
    console.log(`STEP dig for quest: ${b?.name} at ${b?.position}`)
    await bot.dig(b, true)
    await sleep(300)
  }
  await waitChat(start, /Quest complete: Dig dirt, sand or gravel/, 'quest 1 done')
  await waitChat(start, /Community quest done!/, 'community quest done')
  await waitChat(start, /share of the community quest: 1,500 coins/, 'community share')

  // Delivery: 10 of 12 cobblestone go into the storehouse.
  await expect('/give NanoBot cobblestone 12', /Gave 12/, 'give cobblestone')
  await sleep(500)
  await questButton(11, 11, /Handed in 10 items for Deliver cobblestone/, 'hand in')
  await sleep(500)
  if (count('cobblestone') !== 2) throw new Error(`hand in (expected 2 cobblestone left, have ${count('cobblestone')})`)
  const stored = await expect('/data get block 44 -60 45 Items', /cobblestone/, 'storehouse holds the delivery')
  if (!/count: 10\b/.test(stored)) throw new Error(`storehouse (expected 10 cobblestone: ${stored})`)

  await questButton(10, 13, /Claimed 40 coins for Dig dirt/, 'claim quest 1')
  await questButton(11, 13, /Claimed 60 coins for Deliver cobblestone/, 'claim quest 2')
  await expect('/balance', /Balance: 1,875 coins/, 'quest money')
  await expect('/quests status NanoBot', /#1 dig_dirt=claimed 3\/3 #2 deliver_cobblestone=claimed 10\/10 #3 \S+\(reroll\)=open.*community dig_dirt 3\/3 yours 3 done/, 'quest status')
  await expect('/stats', /Quests done: 2/, 'player stats')
  await expect('/stats top quests', /1\. NanoBot 2/, 'stats leaderboard')
}

async function questsSaved() {
  await expect('/quests status NanoBot', /#1 dig_dirt=claimed 3\/3 #2 deliver_cobblestone=claimed 10\/10 #3 \S+\(reroll\)=open.*community dig_dirt 3\/3 yours 3 done/, 'quests saved')
  await expect('/quests list', /storehouse: world 44 -60 45/, 'board and storehouse saved')
  await labelThere('board label after restart')
}

async function second() {
  await expect('/balance', /Balance: 1,875 coins/, 'balance saved')
  await questsSaved()
  await expect('/sell', /sold all you can today/, 'shop limit saved')
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
}, 300_000)

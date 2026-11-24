// Play-tests NanoCore as a real player. Usage: node scenario.mjs <first|second|third>
// "first" runs on a fresh server; "second" and "third" run after restarts and check that everything was saved.
// Prints "SCENARIO PASS <phase>" or "SCENARIO FAIL <phase>: <step>" and exits 0/1.
import { createTestBot } from './lib.mjs'
import { Vec3 } from 'vec3'

const phase = process.argv[2] ?? 'first'
const chat = []
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const itemCount = (name) => bot.inventory.items().filter((i) => i.name === name).reduce((n, i) => n + i.count, 0)

// Mineflayer (through ViaBackwards) sometimes gets kicked for "invalid_player_movement" right after the server
// teleports it mid-move. Real clients don't. During a step marked here such a kick means: rejoin and check the result.
let quirkStep = null
let kicked = false
let bot

function connect() {
  kicked = false
  bot = createTestBot('NanoBot')
  bot.on('messagestr', (t) => {
    chat.push(t)
    console.log('  chat:', t)
  })
  bot.on('kicked', (r) => {
    if (quirkStep && /invalid_player_movement/.test(JSON.stringify(r))) {
      console.log(`STEP ${quirkStep}: bot kicked by the known Mineflayer teleport quirk, rejoining`)
      kicked = true
      return
    }
    console.log(`SCENARIO FAIL ${phase}: kicked ${JSON.stringify(r)}`)
    process.exit(1)
  })
  bot.on('error', (e) => {
    if (kicked) return
    console.log(`SCENARIO FAIL ${phase}: ${e.message}`)
    process.exit(1)
  })
  return new Promise((resolve) => bot.once('spawn', resolve))
}

/** After a quirk kick: join again (up to twice) and let the server settle the player. */
async function rejoinIfKicked() {
  for (let i = 0; i < 2 && kicked; i++) {
    await sleep(2000)
    await connect()
    await sleep(3000)
  }
  if (kicked) throw new Error(`${quirkStep} (kicked again after rejoining)`)
}

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
  // Enter the Nether first (on a glass floor covering the whole chunk, so the push-down always lands on it
  // whatever the random terrain), let the bot settle, then go up to the roof.
  await expect('/execute in minecraft:the_nether run forceload add 0 0 15 15', /[Cc]hunk|force/i, 'load nether chunk', 30000)
  await expect('/execute in minecraft:the_nether run fill 0 99 0 15 99 15 glass', /Successfully filled|blocks? filled/i, 'nether platform')
  await expect('/execute in minecraft:the_nether run fill 0 100 0 15 102 15 air', /Successfully filled|blocks? filled|No blocks/i, 'nether platform air')
  await expect('/execute in minecraft:the_nether run tp NanoBot 8.5 100 8.5', /Teleported/, 'tp into nether')
  await sleep(2000)
  await bot.waitForChunksToLoad()
  await sleep(2000)
  quirkStep = 'nether roof'
  await expect('/tp NanoBot 8.5 129 8.5', /Teleported/, 'tp to nether roof')
  await sleep(2000) // falling onto the roof is a move above it: the server sends the bot down
  if (!kicked && bot.entity.position.y > 127) {
    bot.setControlState('forward', true)
    await sleep(1500)
    bot.setControlState('forward', false)
  }
  await sleep(1500)
  await rejoinIfKicked()
  quirkStep = null
  console.log(`STEP roof: bot at ${bot.entity.position} in ${bot.game.dimension}`)
  if (!String(bot.game.dimension).includes('nether')) throw new Error(`nether roof (bot ended up in ${bot.game.dimension})`)
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
  await shops()
  await panel()
  await events(count)
}

// --- Player shops: NanoBot makes a diamond shop, NanoPal buys and sells, the chest is protected ---
const SHOP = new Vec3(46, -60, 48)

async function palWindowAfter(action, step) {
  const opened = new Promise((resolve) => pal.once('windowOpen', (w) => resolve(w)))
  await action()
  const w = await Promise.race([opened, sleep(5000).then(() => null)])
  if (!w) throw new Error(`${step} (no window opened for the second player)`)
  await sleep(300)
  return w
}

async function shops() {
  await connectPal()
  await expect('/tp NanoBot 46.5 -60 46.5', /Teleported/, 'tp to the shop spot')
  await expect('/tp NanoPal 47.5 -60 49.5', /Teleported/, 'pal to the shop spot')
  await sleep(1500)
  await expect(`/setblock ${SHOP.x} ${SHOP.y} ${SHOP.z} chest`, /Changed the block/i, 'shop chest')
  await expect('/pshop new', /Player shops open at the Grand Market/, 'shops locked until the milestone')
  await expect('/nanocore milestone unlock player_shops', /now unlocked/, 'unlock player shops')
  const diamond = bot.inventory.items().find((i) => i.name === 'diamond')
  if (!diamond) throw new Error('shop (the bot has no diamond to hold)')
  await bot.equip(diamond, 'hand')
  await expect('/pshop new', /right-click the chest or barrel/, 'pick the chest')
  const made = chat.length
  await windowAfter(() => bot.activateBlock(bot.blockAt(SHOP)), 'make the shop (setup window)')
  await waitChat(made, /Shop made!/, 'shop made')
  await click(23, 'mode: both')
  await click(33, 'price +10')
  bot.closeWindow(bot.currentWindow)
  await sleep(300)
  await expect(`/item replace block ${SHOP.x} ${SHOP.y} ${SHOP.z} container.0 with diamond 10`, /Replaced|slot/i, 'stock the shop')
  await expect('/pshop list', /#1 NanoBot diamond at world 46 -60 48: both, sell 11, buy 1, 10 in stock/, 'shop listed')
  await sleep(2500) // labels refresh every second
  await expect('/execute positioned 46.5 -58.3 48.5 if entity @e[type=minecraft:text_display,distance=..0.6]', /Test passed/, 'shop label')
  await expect('/execute positioned 46.5 -58.85 48.5 if entity @e[type=minecraft:item_display,distance=..0.6]', /Test passed/, 'floating item')

  const shopWin = await palWindowAfter(() => pal.activateBlock(pal.blockAt(SHOP)), 'pal opens the shop')
  console.log(`STEP shop window: slot 10 ${shopWin.slots[10]?.name}, 14 ${shopWin.slots[14]?.name}`)
  if (shopWin.slots[10]?.name !== 'diamond' || shopWin.slots[14]?.name !== 'diamond') throw new Error('shop window (no buy/sell buttons)')
  let p0 = palChat.length
  const s0 = chat.length
  await palWindowAfter(() => pal.clickWindow(11, 0, 0), 'pal buys 8')
  await palSees(p0, /You bought 8 .*for 88 coins/, 'pal bought 8')
  await waitChat(s0, /NanoPal bought 8 .*You got 87 coins/, 'owner hears about the sale (2% tax)')
  p0 = palChat.length
  await palWindowAfter(() => pal.clickWindow(14, 0, 0), 'pal sells 1 back')
  await palSees(p0, /You sold 1 .*for 1 coin/, 'pal sold 1')
  pal.closeWindow(pal.currentWindow)
  await sleep(500)
  const palDiamonds = pal.inventory.items().filter((i) => i.name === 'diamond').reduce((n, i) => n + i.count, 0)
  if (palDiamonds !== 7) throw new Error(`shop trade (pal should have 7 diamonds, has ${palDiamonds})`)
  await expect('/balance NanoPal', /NanoPal's balance: 13 coins/, 'pal paid')
  await expect('/balance', /Balance: 1,961 coins/, 'owner paid')
  await expect('/pshop list', /both, sell 11, buy 1, 3 in stock/, 'stock after trades')

  p0 = palChat.length
  console.log('STEP pal tries to break the shop')
  await Promise.race([pal.dig(pal.blockAt(SHOP), true).catch(() => {}), sleep(7000)])
  try { pal.stopDigging() } catch {}
  await palSees(p0, /That's NanoBot's shop/, 'shop protected from others')
  await sleep(500)
  if (bot.blockAt(SHOP)?.name !== 'chest') throw new Error('shop protection (the chest was broken)')

  // A chest the player didn't place can't become their shop.
  await expect('/setblock 48 -60 50 chest', /Changed the block/i, 'a chest pal did not place')
  p0 = palChat.length
  pal.chat('/pshop new')
  await palSees(p0, /right-click the chest or barrel/, 'pal picks a chest')
  p0 = palChat.length
  await pal.activateBlock(pal.blockAt(new Vec3(48, -60, 50)))
  await palSees(p0, /only make a shop from a chest or barrel you placed/, 'no shop from someone else\'s chest')
}

// --- Control panel (/nc): a setting changed from the GUI is saved; players get their own menu ---
async function panel() {
  await expect('/nc help', /the menu with everything/, 'help')
  await expect('/nc help 99', /after editing the files/, 'admin help lines on the last page')
  // Half-typed commands list the ways to finish them instead of "Unknown or incomplete command".
  await expect('/pay', /To use \/pay, type one of these/, 'usage for /pay')
  await expect('/nc eco', /\/nc eco give <player> <amount>/, 'usage for /nc eco')
  await expect('/event all', /mining_rush: /, 'admin event list')
  const win = await windowAfter(() => bot.chat('/nc'), 'open the control panel')
  console.log(`STEP panel: slot 19 ${win.slots[19]?.name}, 31 ${win.slots[31]?.name}, 13 ${win.slots[13]?.name}`)
  if (win.slots[19]?.name !== 'player_head' || win.slots[31]?.name !== 'comparator') throw new Error('control panel layout')
  // Setup checklist > time zone: one click sets it, another puts it back to the server's own.
  await click(13, 'setup checklist')
  await click(10, 'time zone step')
  let s = chat.length
  await click(10, 'pick Pacific')
  await waitChat(s, /Time zone is now America\/Vancouver/, 'time zone saved')
  await click(10, 'time zone step again')
  s = chat.length
  await click(21, 'server time zone')
  await waitChat(s, /Time zone is now the server's own/, 'time zone back')
  bot.closeWindow(bot.currentWindow)
  await sleep(300)
  await windowAfter(() => bot.chat('/nc'), 'reopen the control panel')
  await click(31, 'settings')
  await click(10, 'money and shops')
  await click(15, 'player shop limit')
  s = chat.length
  await click(14, 'limit +1')
  await waitChat(s, /Player shop limit is now 11/, 'setting saved')
  s = chat.length
  await click(12, 'limit -1')
  await waitChat(s, /Player shop limit is now 10/, 'setting back')
  // Typing a value: the window closes, the next chat line is the answer, and the window comes back.
  s = chat.length
  await bot.clickWindow(13, 0, 0)
  await waitChat(s, /Type the new Player shop limit/, 'number prompt')
  s = chat.length
  await windowAfter(() => bot.chat('10'), 'typed value reopens the window')
  await waitChat(s, /Player shop limit is now 10/, 'typed value saved')
  bot.closeWindow(bot.currentWindow)
  await sleep(300)
  const pm = await palWindowAfter(() => pal.chat('/nc'), 'pal opens the player menu')
  if (pm.slots[22]?.name !== 'chest' || pm.slots[29]?.name !== 'gold_ingot' || pm.slots[36]?.name === 'comparator') throw new Error('player menu (wrong buttons)')
  pal.closeWindow(pm)
  await sleep(300)
}

// --- Events: Mining Rush scoring and prizes, a vote with a second player, kit mode across a restart ---
let pal
const palChat = []

async function connectPal() {
  pal = createTestBot('NanoPal')
  pal.on('messagestr', (t) => {
    palChat.push(t)
    console.log('  pal chat:', t)
  })
  pal.on('kicked', (r) => console.log(`STEP pal kicked: ${JSON.stringify(r)}`))
  await new Promise((resolve) => pal.once('spawn', resolve))
  await sleep(1500)
}

async function palSees(start, pattern, step, timeout = 8000) {
  const until = Date.now() + timeout
  while (Date.now() < until) {
    if (palChat.slice(start).some((t) => pattern.test(t))) return
    await sleep(100)
  }
  throw new Error(`${step} (the second player saw no line matching ${pattern})`)
}

async function events(count) {
  if (!pal) await connectPal()
  await expect('/tp NanoBot 42.5 -60 42.5', /Teleported/, 'back to the quest area')
  await sleep(1500)

  // Mining Rush: join, mine two ores, check the score, end with prizes.
  await expect('/event start mining_rush', /Event: Mining Rush/, 'start mining rush')
  await expect('/event join', /You're in Mining Rush/, 'join event')
  await expect('/event next', /Mining Rush: Mine!/, 'mining phase')
  await expect('/setblock 44 -61 40 coal_ore', /Changed the block/, 'coal ore')
  await expect('/setblock 40 -61 40 diamond_ore', /Changed the block/, 'diamond ore')
  await sleep(500)
  for (const [x, z] of [[44, 40], [40, 40]]) {
    const b = bot.blockAt(new Vec3(x, -61, z))
    console.log(`STEP mine for the event: ${b?.name} at ${b?.position}`)
    await bot.dig(b, true)
    await sleep(300)
  }
  await sleep(1500)
  await expect('/event top', /1\. NanoBot 11/, 'event score')
  const sb = bot.scoreboard?.sidebar
  const sbTitle = sb ? JSON.stringify(sb.title) : 'none'
  console.log(`STEP sidebar: ${sbTitle.slice(0, 200)} (${sb?.items?.length ?? 0} lines seen by the bot)`)
  if (!sb || !sbTitle.includes('Mining Rush')) throw new Error('event sidebar (no sidebar titled Mining Rush)')
  await expect('/event addtime 5', /Changed the time by 5 minutes/, 'add time')
  const start = chat.length
  await expect('/event end', /Mining Rush is over!/, 'end mining rush')
  await waitChat(start, /1st NanoBot \(11\)/, 'results line')
  await waitChat(start, /You came 1st! You got 250 coins and a trophy/, 'first prize')
  await sleep(500)
  if (count('gold_ingot') !== 1) throw new Error(`trophy (expected 1 gold ingot trophy, have ${count('gold_ingot')})`)
  await expect('/balance', /Balance: 2,211 coins/, 'event prize paid')
  await expect('/event history', /Mining Rush: 1\. NanoBot/, 'event history')

  // Vote: NanoBot votes for NanoPal, who wins the vote.
  await expect('/event start vote_test', /Event: Vote Test/, 'start vote test')
  await expect('/event join', /You're in Vote Test/, 'bot joins vote')
  let p0 = palChat.length
  pal.chat('/event join')
  await palSees(p0, /You're in Vote Test/, 'pal joins vote')
  await expect('/event next', /Voting is open/, 'voting phase')
  const voteWin = await windowAfter(() => bot.chat('/vote'), 'open vote menu')
  console.log(`STEP vote menu: slot 0 ${voteWin.slots[0]?.name}`)
  if (voteWin.slots[0]?.name !== 'player_head') throw new Error('vote menu (no player to vote for in slot 0)')
  const vs = chat.length
  await windowAfter(() => bot.clickWindow(0, 0, 0), 'vote click')
  await waitChat(vs, /You voted NanoPal as your 1st pick/, 'vote counted')
  bot.closeWindow(bot.currentWindow)
  await sleep(300)
  await expect('/event top', /secret until the end/, 'votes stay secret')
  const ve = chat.length
  await expect('/event end', /Vote Test is over!/, 'end vote')
  await waitChat(ve, /1st NanoPal \(3\)/, 'vote winner')

  // Kit mode: everyone is in, own things are saved and swapped for the kit; building is off for players.
  await expect('/give NanoPal dirt 1', /Gave 1/, 'give pal dirt')
  await expect('/event start kit_test', /Event: Kit Test/, 'start kit test')
  await sleep(1500)
  console.log(`STEP kit: diamond=${count('diamond')} stone_sword=${count('stone_sword')} bread=${count('bread')}`)
  if (count('diamond') !== 0 || count('stone_sword') !== 1 || count('bread') !== 3) throw new Error('kit (inventory not swapped for the kit)')
  console.log('STEP pal tries to build during the event')
  await expect('/tp NanoPal 30.5 -60 30.5', /Teleported/, 'pal to open ground')
  await expect('/give NanoPal dirt 1', /Gave 1/, 'give pal dirt (the kit replaced the first)')
  await sleep(500)
  const dirt = pal.inventory.items().find((i) => i.name === 'dirt')
  if (!dirt) throw new Error('pal has no dirt to place')
  await pal.equip(dirt, 'hand')
  const ground = pal.blockAt(new Vec3(31, -61, 30))
  p0 = palChat.length
  await pal.placeBlock(ground, new Vec3(0, 1, 0)).catch(() => {})
  await palSees(p0, /Building is off here during Kit Test/, 'building rule')
  pal.end()
  await sleep(500)
  // The server restarts now with the event still running; the second phase ends it.
}

async function kitAfterRestart() {
  const count = itemCount
  await expect('/event', /Kit Test/, 'event still running after the restart')
  if (count('stone_sword') !== 1) throw new Error(`kit after restart (stone_sword=${count('stone_sword')})`)
  const s = chat.length
  await expect('/event end', /Kit Test is over!/, 'end kit test')
  await waitChat(s, /Your own things are back/, 'own things back')
  await sleep(1000)
  console.log(`STEP after kit: diamond=${count('diamond')} gold_ingot=${count('gold_ingot')} stone_sword=${count('stone_sword')}`)
  if (count('diamond') !== 5 || count('gold_ingot') !== 1 || count('stone_sword') !== 0) throw new Error('kit restore (own inventory not back)')
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

/** An admin refresh on the same day gives a new board; nothing of the old progress may stick to it. */
async function freshBoard() {
  await expect('/quests refresh', /New quests are up/, 'admin refresh')
  const s = await expect('/quests status NanoBot', /Quests for NanoBot:/, 'status after refresh')
  if (/=claimed|=active|=done|\(reroll\)/.test(s.split('|')[0])) throw new Error(`status after refresh (old progress kept: ${s})`)
}

async function storehouseLocked() {
  await expect('/deop NanoBot', /no longer a server operator/, 'deop')
  await sleep(500)
  const start = chat.length
  console.log('STEP open the storehouse as a player')
  await bot.activateBlock(bot.blockAt(new Vec3(44, -60, 45)))
  await waitChat(start, /That's the town storehouse/, 'storehouse locked for players')
}

async function second() {
  await expect('/balance', /Balance: 2,211 coins/, 'balance saved')
  await questsSaved()
  await freshBoard()
  await expect('/sell', /sold all you can today/, 'shop limit saved')
  await expect('/nanocore milestone list', /nether: unlocked/, 'milestone saved')
  await expect('/nanocore stats NanoBot place.', /place\.dirt=1/, 'stats saved')
  await expect('/nanocore area list', /town: .*radius 50/, 'area saved')
  await expect('/pshop list', /#1 NanoBot diamond at world 46 -60 48: both, sell 11, buy 1, 3 in stock/, 'shop saved')
  await sleep(2500)
  await expect('/execute positioned 46.5 -58.3 48.5 if entity @e[type=minecraft:text_display,distance=..0.6]', /Test passed/, 'shop label after restart')
  await kitAfterRestart()
  await storehouseLocked()
}

async function third() {
  const s = await expect('/quests status NanoBot', /Quests for NanoBot:/, 'board after restart')
  if (/=claimed|=active|=done|\(reroll\)/.test(s.split('|')[0])) throw new Error(`old progress came back after a restart: ${s}`)
  await expect('/balance', /Balance: 2,216 coins/, 'balance after second restart (kit test paid 5 for taking part)')
  await connectPal()
  await palSees(0, /Your own things are back/, 'offline player gets their things back on joining', 10000)
  pal.end()
  await partsTests()
}

// --- Event gameplay parts: waves and a boss, a collection list, deliveries, holding a zone, a checklist ---
async function partsTests() {
  await expect('/tp NanoBot 20.5 -60 20.5', /Teleported/, 'tp to open ground')
  await sleep(1500)
  let s = chat.length
  await expect('/event start waves_test', /Event: Waves Test/, 'start waves test')
  await waitChat(s, /Wave 1 of 1/, 'wave 1 spawned')
  await sleep(1000)
  await expect('/execute if entity @e[type=minecraft:zombie]', /Test passed/, 'wave mobs are there')
  s = chat.length
  await expect('/kill @e[type=minecraft:zombie]', /Killed/, 'kill the wave')
  await waitChat(s, /Test Boss has appeared/, 'boss after the last wave', 10000)
  s = chat.length
  await sleep(1000)
  await expect('/kill @e[type=minecraft:zombie]', /Killed/, 'kill the boss')
  await waitChat(s, /Victory!/, 'waves cleared', 10000)
  await waitChat(s, /Waves Test: Done/, 'cleared ends the phase', 10000)
  await expect('/event end', /Waves Test is over!/, 'end waves test')

  s = chat.length
  await expect('/event start collect_test', /Event: Collect Test/, 'start collect test')
  await waitChat(s, /The list: (dirt, stick|stick, dirt)/, 'list announced')
  await expect('/event join', /You're in Collect Test/, 'join collect test')
  await expect('/give NanoBot stick 1', /Gave 1/, 'give stick')
  await expect('/give NanoBot dirt 1', /Gave 1/, 'give dirt')
  await sleep(500)
  s = chat.length
  await expect('/event handin', /NanoBot handed in the full list, 1st/, 'hand in the list')
  await waitChat(s, /Collect Test: Done/, 'first finisher ends the phase')
  await expect('/event end', /Collect Test is over!/, 'end collect test')

  s = chat.length
  await expect('/event start deliver_test', /Event: Deliver Test/, 'start deliver test')
  await expect('/give NanoBot cobblestone 7', /Gave 7/, 'give cobblestone')
  await expect('/give NanoBot oak_planks 3', /Gave 3/, 'give planks')
  await sleep(500)
  s = chat.length
  await expect('/event deliver', /Delivered 8 items/, 'deliver (5 cobblestone taken, 2 kept)')
  await waitChat(s, /Deliver Test: Done/, 'needs met end the phase')
  if (itemCount('cobblestone') < 2) throw new Error(`deliver (only what was needed should be taken; cobblestone left ${itemCount('cobblestone')})`)
  await expect('/event end', /Deliver Test is over!/, 'end deliver test')

  await expect('/nanocore area circle hilltest 3', /Area hilltest saved/, 'hill area')
  await expect('/event start hold_test', /Event: Hold Test/, 'start hold test')
  await expect('/event join', /You're in Hold Test/, 'join hold test')
  await sleep(6500)
  await expect('/event top', /1\. NanoBot [2-9]/, 'holding the zone alone scores')
  await expect('/event end', /Hold Test is over!/, 'end hold test')

  s = chat.length
  await expect('/event start checklist_test', /Event: Checklist Test/, 'start checklist test')
  await expect('/event check 1', /Done: First task/, 'tick task 1')
  await expect('/event check 2', /Done: Second task/, 'tick task 2')
  await waitChat(s, /Checklist Test is over!/, 'checklist done ends the event', 10000)
  await waitChat(s, /The End.*now unlocked/, 'event unlocks its milestone', 10000)
}

connect().then(async () => {
  await sleep(1500)
  try {
    await ({ first, second, third }[phase] ?? first)()
    console.log(`SCENARIO PASS ${phase}`)
    bot.end()
    setTimeout(() => process.exit(0), 300)
  } catch (err) {
    console.log(`SCENARIO FAIL ${phase}: ${err.message}`)
    bot.end()
    setTimeout(() => process.exit(1), 300)
  }
})
setTimeout(() => {
  console.log(`SCENARIO FAIL ${phase}: timed out`)
  process.exit(1)
}, 300_000)

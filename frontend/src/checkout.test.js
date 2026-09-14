import test from 'node:test'
import assert from 'node:assert/strict'
import { createCheckoutLifecycle } from './checkout.js'

const firstPayload = { memberId: 'M001', payStatus: 'PENDING', items: [{ productId: 'P001', quantity: 1 }] }
const changedPayload = { memberId: 'M001', payStatus: 'PENDING', items: [{ productId: 'P002', quantity: 1 }] }

test('retries an ambiguous checkout with the original key and payload even when the visible cart is empty', async () => {
  const lifecycle = createCheckoutLifecycle(() => '00000000-0000-4000-8000-000000000001')
  const sent = []
  await lifecycle.submit(firstPayload, async (request) => {
    sent.push(request)
    throw new Error('network timeout')
  })

  const blocked = await lifecycle.submit({ ...changedPayload, items: [] }, async () => assert.fail('must require explicit retry'))
  assert.equal(blocked.status, 'retry-required')
  const result = await lifecycle.retry(async (request) => {
    sent.push(request)
    return { data: { orderId: 'Ms1' } }
  })

  assert.equal(result.status, 'success')
  assert.deepEqual(sent[1], sent[0])
  assert.equal(sent[1].requestId, '00000000-0000-4000-8000-000000000001')
})

test('prevents simultaneous submits from issuing a second request', async () => {
  const lifecycle = createCheckoutLifecycle(() => '00000000-0000-4000-8000-000000000002')
  let resolvePost
  let calls = 0
  const first = lifecycle.submit(firstPayload, () => {
    calls += 1
    return new Promise((resolve) => { resolvePost = resolve })
  })
  const second = await lifecycle.submit(firstPayload, () => assert.fail('must not post twice'))
  assert.equal(second.status, 'in-flight')
  assert.equal(calls, 1)
  resolvePost({ data: { orderId: 'Ms2' } })
  assert.equal((await first).status, 'success')
})

test('success rotates the key and a definitive rejection allows a corrected payload', async () => {
  const ids = [
    '00000000-0000-4000-8000-000000000003',
    '00000000-0000-4000-8000-000000000004',
    '00000000-0000-4000-8000-000000000005'
  ]
  const lifecycle = createCheckoutLifecycle(() => ids.shift())
  const first = await lifecycle.submit(firstPayload, async () => ({ data: { orderId: 'Ms3' } }))
  assert.equal(first.status, 'success')

  const rejected = await lifecycle.submit(firstPayload, async () => {
    throw { response: { status: 409 } }
  })
  assert.equal(rejected.status, 'failure')
  const corrected = await lifecycle.submit(changedPayload, async (request) => request)
  assert.equal(corrected.status, 'success')
  assert.equal(corrected.result.requestId, '00000000-0000-4000-8000-000000000005')
})

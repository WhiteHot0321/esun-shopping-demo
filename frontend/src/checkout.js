export const samePayload = (left, right) => JSON.stringify(left) === JSON.stringify(right)

export const isAmbiguousCheckoutFailure = (error) =>
  !error.response || error.response.status >= 500

export const createCheckoutLifecycle = (newRequestId = () => crypto.randomUUID()) => {
  let attempt = null
  let submitting = false

  const send = async (post) => {
    submitting = true
    try {
      const result = await post({ ...attempt.payload, requestId: attempt.requestId })
      attempt = null
      return { status: 'success', result }
    } catch (error) {
      if (!isAmbiguousCheckoutFailure(error)) {
        attempt = null
      }
      return { status: 'failure', error }
    } finally {
      submitting = false
    }
  }

  return {
    get attempt() {
      return attempt
    },
    get isSubmitting() {
      return submitting
    },
    async submit(payload, post) {
      if (submitting) {
        return { status: 'in-flight' }
      }
      if (attempt && !samePayload(attempt.payload, payload)) {
        return { status: 'retry-required' }
      }
      if (!attempt) {
        attempt = { requestId: newRequestId(), payload }
      }
      return send(post)
    },
    async retry(post) {
      if (submitting) {
        return { status: 'in-flight' }
      }
      if (!attempt) {
        return { status: 'no-attempt' }
      }
      return send(post)
    }
  }
}

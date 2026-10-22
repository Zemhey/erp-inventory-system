import { test as base, expect } from '@playwright/test'
import { ApiClient } from './helpers/api'
import { readAdminToken } from './helpers/auth'
import { readRunInfo, type RunInfo } from './helpers/run'

interface Fixtures {
  run: RunInfo
  api: ApiClient
}

export const test = base.extend<Fixtures>({
  run: async ({}, use) => {
    await use(readRunInfo())
  },
  api: async ({ request }, use) => {
    await use(new ApiClient(request, readAdminToken()))
  },
})

export { expect }

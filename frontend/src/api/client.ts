import axios from 'axios'

const client = axios.create({
  baseURL: '',
  withCredentials: true,
})

const MUTATING = new Set(['post', 'put', 'patch', 'delete'])

function readXsrfToken(): string | undefined {
  return document.cookie
    .split('; ')
    .find(r => r.startsWith('XSRF-TOKEN='))
    ?.split('=')[1]
}

// The XSRF-TOKEN cookie is only emitted once a request has passed through the
// server's CsrfCookieFilter. A POST is the first thing a user does on the
// login/register pages, so without this the first mutating request can race the
// app-load GET /api/auth/me and be rejected with 403. Materialize the cookie with
// a bootstrap GET (tolerating its 401) before attaching the header. The in-flight
// promise is shared so concurrent submits trigger at most one bootstrap.
let bootstrap: Promise<void> | null = null
function ensureCsrfToken(): Promise<void> {
  if (readXsrfToken()) return Promise.resolve()
  if (!bootstrap) {
    bootstrap = axios
      .get('/api/auth/me', { withCredentials: true })
      .catch(() => undefined)
      .then(() => undefined)
      .finally(() => {
        bootstrap = null
      })
  }
  return bootstrap
}

client.interceptors.request.use(async config => {
  if (config.method && MUTATING.has(config.method.toLowerCase())) {
    await ensureCsrfToken()
    const token = readXsrfToken()
    if (token) {
      config.headers['X-XSRF-TOKEN'] = decodeURIComponent(token)
    }
  }
  return config
})

export default client

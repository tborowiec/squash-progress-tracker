import axios from 'axios'

const client = axios.create({
  baseURL: '',
  withCredentials: true,
})

const MUTATING = new Set(['post', 'put', 'patch', 'delete'])

client.interceptors.request.use(config => {
  if (config.method && MUTATING.has(config.method.toLowerCase())) {
    const token = document.cookie
      .split('; ')
      .find(r => r.startsWith('XSRF-TOKEN='))
      ?.split('=')[1]
    if (token) {
      config.headers['X-XSRF-TOKEN'] = decodeURIComponent(token)
    }
  }
  return config
})

export default client

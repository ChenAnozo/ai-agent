import axios from 'axios'

export const BASE_URL = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8123/api').replace(/\/$/, '')
export const request = axios.create({ baseURL: BASE_URL, timeout: 60000 })

# syntax=docker/dockerfile:1
#
# React frontend: built with Vite, served by an unprivileged nginx. The API Gateway URL that the BROWSER calls is
# fixed at build time (VITE_API_BASE_URL); with the default compose file that is http://localhost:8080/api.
#
#   docker build -f docker/frontend.Dockerfile -t grandvista/frontend .

FROM node:24-alpine AS build
WORKDIR /app
COPY grandvista-frontend/package.json grandvista-frontend/package-lock.json ./
RUN npm ci
COPY grandvista-frontend/ ./
ARG VITE_API_BASE_URL=http://localhost:8080/api
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

FROM nginxinc/nginx-unprivileged:stable-alpine AS runtime
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080

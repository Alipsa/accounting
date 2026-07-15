<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>${title}</h2>
  <div class="metrics">
    <div class="metric">
      <span class="metric-label">${outputVatLabel}</span>
      <span class="metric-value">${outputTotal}</span>
    </div>
    <div class="metric">
      <span class="metric-label">${inputVatLabel}</span>
      <span class="metric-value">${inputTotal}</span>
    </div>
    <div class="metric">
      <span class="metric-label">${netLabel}</span>
      <span class="metric-value">${netTotal}</span>
    </div>
  </div>
  <table class="report-table vat-report-table">
    <colgroup>
      <col class="code-col">
      <col class="label-col">
      <col class="amount-col">
      <col class="amount-col">
      <col class="amount-col">
    </colgroup>
    <thead>
      <tr>
        <th class="code">${tableHeaders[0]}</th>
        <th>${tableHeaders[1]}</th>
        <th class="number">${tableHeaders[2]}</th>
        <th class="number">${tableHeaders[3]}</th>
        <th class="number">${tableHeaders[4]}</th>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <tr>
          <td class="code">${row[0]}</td>
          <td>${row[1]}</td>
          <td class="number">${row[2]}</td>
          <td class="number">${row[3]}</td>
          <td class="number">${row[4]}</td>
        </tr>
      </#list>
    </tbody>
  </table>
</@layout.page>

<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>${title}</h2>
  <p class="lead">${lead}</p>
  <ul class="summary">
    <#list summaryLines as line>
      <li>${line}</li>
    </#list>
  </ul>
  <table class="report-table voucher-list-table">
    <colgroup>
      <col class="date-col">
      <col class="voucher-col">
      <col class="series-col">
      <col class="text-col">
      <col class="status-col">
      <col class="amount-col">
      <col class="amount-col">
    </colgroup>
    <thead>
      <tr>
        <th class="date">${tableHeaders[0]}</th>
        <th>${tableHeaders[1]}</th>
        <th>${tableHeaders[2]}</th>
        <th>${tableHeaders[3]}</th>
        <th>${tableHeaders[4]}</th>
        <th class="number">${tableHeaders[5]}</th>
        <th class="number">${tableHeaders[6]}</th>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <tr>
          <td class="date">${row[0]}</td>
          <td>${row[1]}</td>
          <td>${row[2]}</td>
          <td>${row[3]}</td>
          <td>${row[4]}</td>
          <td class="number">${row[5]}</td>
          <td class="number">${row[6]}</td>
        </tr>
      </#list>
    </tbody>
  </table>
</@layout.page>
